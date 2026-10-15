package com.soma.wes.global

import com.soma.wes.global.cache.RedisSharedCache
import com.soma.wes.global.cache.SharedCache
import com.soma.wes.global.cluster.RedisSchedulerLeadership
import com.soma.wes.global.cluster.SchedulerLeadership
import com.soma.wes.support.DatabaseClearExtension
import com.soma.wes.support.SharedValkey
import com.soma.wes.support.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Redis 를 쓰는 두 기능을 켜면(#313) 앱이 Redis 구현을 꽂는다 — 리더 임대(혼자 뜬 서버는 첫 갱신에서 리더)와 공유 캐시. 각 규칙은
 * `RedisSchedulerLeadershipUnitTest`·`RedisSharedCacheUnitTest`가 본다 — 여기는 설정·자동 구성이 이어지는지만.
 */
@SpringBootTest(
    properties = [
        "app.cluster.leader.enabled=true",
        "app.cluster.leader.key=test:wiring-leader",
        "app.cache.shared.enabled=true",
    ],
)
@Import(TestcontainersConfiguration::class)
@ExtendWith(DatabaseClearExtension::class)
class RedisWiringTest @Autowired constructor(
    private val leadership: SchedulerLeadership,
    private val sharedCache: SharedCache,
) {

    companion object {
        private const val WAIT_MILLIS = 5_000L
        private const val POLL_MILLIS = 50L

        @JvmStatic
        @DynamicPropertySource
        fun redis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { SharedValkey.host }
            registry.add("spring.data.redis.port") { SharedValkey.mappedPort }
        }
    }

    @Test
    fun `리더 임대를 켜면 Redis 임대가 꽂히고 혼자 뜬 서버는 곧 리더가 된다`() {
        assertThat(leadership).isInstanceOf(RedisSchedulerLeadership::class.java)
        assertThat(becomesLeader()).isTrue()
    }

    @Test
    fun `공유 캐시를 켜면 Redis 공유 캐시가 꽂힌다`() {
        assertThat(sharedCache).isInstanceOf(RedisSharedCache::class.java)
    }

    private fun becomesLeader(): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (leadership.isLeader()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }
}
