package com.soma.wes.global

import com.soma.wes.global.cluster.LeaderLeaseStore
import com.soma.wes.global.cluster.LeasedSchedulerLeadership
import com.soma.wes.global.cluster.PostgresLeaderLeaseStore
import com.soma.wes.global.cluster.SchedulerLeadership
import com.soma.wes.support.DatabaseClearExtension
import com.soma.wes.support.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * 리더 임대를 Postgres 로 고르면(#336 ADR-0002) 앱이 임대 행 저장소를 꽂고, 혼자 뜬 서버는 첫 갱신에서 리더가 된다. Redis 없이 뜬다.
 * 규칙은 `PostgresLeaderLeaseTest`가 본다 — 여기는 설정·자동 구성이 이어지는지만.
 */
@SpringBootTest(
    properties = [
        "app.cluster.leader.enabled=true",
        "app.cluster.leader.store=postgres",
        "app.cluster.leader.key=test:wiring-leader",
    ],
)
@Import(TestcontainersConfiguration::class)
@ExtendWith(DatabaseClearExtension::class)
class PostgresLeaderWiringTest @Autowired constructor(
    private val leadership: SchedulerLeadership,
    private val store: LeaderLeaseStore,
) {

    companion object {
        private const val WAIT_MILLIS = 5_000L
        private const val POLL_MILLIS = 50L
    }

    @Test
    fun `리더 임대를 Postgres 로 고르면 임대 행 저장소가 꽂히고 혼자 뜬 서버는 곧 리더가 된다`() {
        assertThat(leadership).isInstanceOf(LeasedSchedulerLeadership::class.java)
        assertThat(store).isInstanceOf(PostgresLeaderLeaseStore::class.java)
        assertThat(becomesLeader()).isTrue()
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
