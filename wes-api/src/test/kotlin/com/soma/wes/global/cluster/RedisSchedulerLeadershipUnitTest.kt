package com.soma.wes.global.cluster

import com.soma.wes.global.config.ClusterLeaderProperties
import com.soma.wes.support.SharedValkey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 리더 임대의 계약(#313 ADR-0002) — 한 번에 한 서버만 리더이고, 리더가 내려가거나 임대가 끝나면 다른 서버가 이어받으며, 갱신이 끊긴 리더는
 * 임대가 끝나기 전에 스스로 리더가 아니라고 본다. 운영과 같은 엔진(Valkey)에 붙는다.
 */
class RedisSchedulerLeadershipUnitTest {

    private val properties = ClusterLeaderProperties(enabled = true, key = "test:scheduler-leader")
    private val clock = MutableClock()
    private val events = mutableListOf<Any>()

    private val connectionFactory = connect(SharedValkey.mappedPort)
    private val redis = StringRedisTemplate(connectionFactory)

    @BeforeEach
    fun clearLease() {
        redis.delete(properties.key)
    }

    @AfterEach
    fun disconnect() {
        connectionFactory.destroy()
    }

    @Test
    fun `두 서버가 함께 갱신해도 리더는 하나다`() {
        // given
        val first = server()
        val second = server()

        // when
        first.tick()
        second.tick()
        first.tick()
        second.tick()

        // then
        assertSoftly { softly ->
            softly.assertThat(first.isLeader()).isTrue()
            softly.assertThat(second.isLeader()).isFalse()
            softly.assertThat(events).hasSize(1)
        }
    }

    @Test
    fun `리더가 내려가며 임대를 돌려주면 다른 서버가 다음 갱신에서 바로 이어받는다`() {
        // given
        val leaving = server().apply { tick() }
        val staying = server().apply { tick() }

        // when — 배포로 리더가 내려간다
        leaving.stop()
        staying.tick()

        // then
        assertSoftly { softly ->
            softly.assertThat(leaving.isLeader()).isFalse()
            softly.assertThat(staying.isLeader()).isTrue()
            softly.assertThat(events).hasSize(2)
        }
    }

    @Test
    fun `내려간 뒤에 늦게 온 갱신은 임대를 다시 얻지 않는다`() {
        // given — 갱신 하나가 반납과 엇갈려 늦게 도착하는 경우(PR #315 리뷰)
        val leaving = server().apply { tick() }
        val staying = server()

        // when
        leaving.stop()
        leaving.tick()
        staying.tick()

        // then
        assertSoftly { softly ->
            softly.assertThat(leaving.isLeader()).isFalse()
            softly.assertThat(staying.isLeader()).isTrue()
        }
    }

    @Test
    fun `임대가 끝나면 다른 서버가 이어받고 옛 리더는 다음 갱신에서 리더가 아님을 안다`() {
        // given — 리더가 멈춰 갱신하지 못하는 사이 키가 만료됐다
        val stalled = server().apply { tick() }
        val other = server()
        redis.delete(properties.key)

        // when
        other.tick()
        stalled.tick()

        // then
        assertSoftly { softly ->
            softly.assertThat(other.isLeader()).isTrue()
            softly.assertThat(stalled.isLeader()).isFalse()
        }
    }

    @Test
    fun `갱신이 끊긴 리더는 임대가 끝나기 전에 스스로 리더가 아니라고 본다`() {
        // given
        val leader = server().apply { tick() }

        // when — 갱신 없이 ttl 에서 여유를 뺀 만큼 지난다
        clock.advance(properties.ttl.minusSeconds(1))

        // then — 키는 아직 살아 있어도(다른 서버는 아직 못 얻음) 이 서버는 이미 멈춰 있다
        assertThat(leader.isLeader()).isFalse()
    }

    @Test
    fun `Redis 에 닿지 않으면 리더가 되지 않는다`() {
        // given — 아무도 듣지 않는 포트
        val unreachable = connect(port = 1)
        val isolated = server(StringRedisTemplate(unreachable))

        // when
        isolated.tick()

        // then
        assertThat(isolated.isLeader()).isFalse()
        unreachable.destroy()
    }

    private fun server(template: StringRedisTemplate = redis) =
        RedisSchedulerLeadership(template, properties, ApplicationEventPublisher { events += it }, clock)

    private fun connect(port: Int) = LettuceConnectionFactory(RedisStandaloneConfiguration(SharedValkey.host, port)).apply {
        afterPropertiesSet()
        start()
    }

    /** 앞으로 돌릴 수 있는 시계. Redis 의 키 만료는 실제 시간이라 이 시계와 무관하다. */
    private class MutableClock : Clock() {
        private var instant: Instant = Instant.parse("2026-10-15T12:00:00Z")

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }

        override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }
}
