package com.soma.wes.global.cache

import com.soma.wes.global.config.SharedCacheProperties
import com.soma.wes.support.SharedValkey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 공유 캐시의 계약(#313 ADR-0002) — 맡긴 값을 남은 시간만큼 담고 한 번에 읽으며, Redis 가 안 되면 예외 없이 빈 결과로 답하고 한동안 부르지
 * 않는다(요청이 Redis 장애를 기다리지 않게). 운영과 같은 엔진(Valkey)에 붙는다.
 */
class RedisSharedCacheUnitTest {

    private val properties = SharedCacheProperties(enabled = true, openFor = Duration.ofSeconds(30))
    private val connections = mutableListOf<LettuceConnectionFactory>()

    companion object {
        private const val WAIT_MILLIS = 2_000L
        private const val POLL_MILLIS = 20L

        /** 회로가 열린 뒤의 호출은 Redis 에 닿지 않는다 — 시간 초과(500ms)보다 한참 짧아야 한다. */
        private const val OPEN_CALL_MAX_MILLIS = 50L
    }

    @AfterEach
    fun disconnect() {
        connections.forEach { it.destroy() }
    }

    @Test
    fun `맡긴 값은 남은 시간만큼 담기고 한 번에 읽힌다`() {
        // given
        val template = template(SharedValkey.mappedPort)
        template.delete(listOf("test:a", "test:b", "test:c"))
        val cache = RedisSharedCache(template, properties, Clock.systemUTC())

        // when
        cache.putLater("test:a", "A", Duration.ofMinutes(5))
        cache.putLater("test:b", "B", Duration.ofMinutes(5))
        val stored = waitFor { cache.getAll(listOf("test:a", "test:b", "test:c")).takeIf { it.size == 2 } }

        // then
        assertSoftly { softly ->
            softly.assertThat(stored).containsExactlyInAnyOrderEntriesOf(mapOf("test:a" to "A", "test:b" to "B"))
            softly.assertThat(template.getExpire("test:a", TimeUnit.SECONDS)).isBetween(290L, 300L)
        }
    }

    @Test
    fun `남은 시간이 없는 값은 맡기지 않는다`() {
        // given
        val template = template(SharedValkey.mappedPort)
        template.delete("test:expired")
        val cache = RedisSharedCache(template, properties, Clock.systemUTC())

        // when
        cache.putLater("test:expired", "X", Duration.ZERO)
        Thread.sleep(POLL_MILLIS * 5)

        // then
        assertThat(template.hasKey("test:expired")).isFalse()
    }

    @Test
    fun `Redis 에 닿지 않으면 빈 결과로 답하고 그 뒤로는 부르지 않는다`() {
        // given — 아무도 듣지 않는 포트
        val cache = RedisSharedCache(template(port = 1), properties, Clock.systemUTC())

        // when
        val first = cache.getAll(listOf("test:a"))
        val startedAt = System.nanoTime()
        val second = cache.getAll(listOf("test:a"))
        val secondMillis = (System.nanoTime() - startedAt) / 1_000_000

        // then
        assertSoftly { softly ->
            softly.assertThat(first).isEmpty()
            softly.assertThat(second).isEmpty()
            softly.assertThat(secondMillis).isLessThan(OPEN_CALL_MAX_MILLIS)
        }
    }

    private fun template(port: Int): StringRedisTemplate {
        val client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build()
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration(SharedValkey.host, port), client).apply {
            afterPropertiesSet()
            start()
        }
        connections += factory
        return StringRedisTemplate(factory)
    }

    private fun <T : Any> waitFor(probe: () -> T?): T? {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }
}
