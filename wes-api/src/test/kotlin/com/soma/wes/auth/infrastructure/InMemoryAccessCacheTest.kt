package com.soma.wes.auth.infrastructure

import com.soma.wes.auth.config.AccessCacheProperties
import com.soma.wes.auth.dto.AccessCacheKeyDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class InMemoryAccessCacheTest {

    private val clock = MutableClock()
    private val cache = InMemoryAccessCache(AccessCacheProperties(enabled = true, ttl = Duration.ofSeconds(30)), clock)
    private val key = AccessCacheKeyDto.userStatus(1)

    @Test
    fun `TTL 안에서는 담긴 값을 돌려주고 지나면 다시 읽는다`() {
        // given
        var loads = 0
        val load = { ++loads }

        // when
        val first = cache.get(key, load)
        clock.advance(Duration.ofSeconds(29))
        val withinTtl = cache.get(key, load)
        clock.advance(Duration.ofSeconds(1))
        val afterTtl = cache.get(key, load)

        // then
        assertSoftly { softly ->
            softly.assertThat(first).isEqualTo(1)
            softly.assertThat(withinTtl).isEqualTo(1)
            softly.assertThat(afterTtl).isEqualTo(2)
        }
    }

    @Test
    fun `null도 값으로 담는다`() {
        // given
        var loads = 0

        // when
        cache.get(key) { loads++; null }
        val again = cache.get(key) { loads++; "다시 읽음" }

        // then
        assertSoftly { softly ->
            softly.assertThat(again).isNull()
            softly.assertThat(loads).isEqualTo(1)
        }
    }

    @Test
    fun `지우면 TTL 안이라도 다시 읽고 다른 키는 그대로다`() {
        // given
        val other = AccessCacheKeyDto.workspace(1)
        cache.get(key) { "옛 값" }
        cache.get(other) { "작업공간" }

        // when
        cache.evict(listOf(key))

        // then
        assertSoftly { softly ->
            softly.assertThat(cache.get(key) { "새 값" }).isEqualTo("새 값")
            softly.assertThat(cache.get(other) { "다시 읽음" }).isEqualTo("작업공간")
        }
    }

    @Test
    fun `종류가 다르면 같은 id라도 다른 키다`() {
        // when
        cache.get(AccessCacheKeyDto.workspace(7)) { "작업공간" }

        // then
        assertThat(cache.get(AccessCacheKeyDto.studioActive(7)) { "스튜디오" }).isEqualTo("스튜디오")
    }

    /** 앞으로 돌릴 수 있는 시계. */
    private class MutableClock : Clock() {
        private var instant: Instant = Instant.parse("2026-10-13T12:00:00Z")

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }

        override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }
}
