package com.soma.wes.global.cluster

import com.soma.wes.global.config.AnalysisPoolRoute
import com.soma.wes.global.config.ClusterLeaderProperties
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** 임대를 `scheduler_leader_lease` 행(V45)에 둘 때의 리더 계약(#336 ADR-0002). Flyway가 만든 테이블에 붙는다. */
@IntegrationTest
class PostgresLeaderLeaseTest @Autowired constructor(
    private val jdbcClient: JdbcClient,
) : LeaderLeaseContract() {

    override val properties = ClusterLeaderProperties(enabled = true, store = "postgres", key = "test:scheduler-leader")

    override val store: LeaderLeaseStore = PostgresLeaderLeaseStore(jdbcClient, properties, AnalysisPoolRoute())

    override fun unreachableStore(): LeaderLeaseStore {
        val nowhere = DriverManagerDataSource("jdbc:postgresql://localhost:1/nowhere?connectTimeout=1", "nobody", "nothing")
        return PostgresLeaderLeaseStore(JdbcClient.create(nowhere), properties, AnalysisPoolRoute())
    }

    override fun expireLease() {
        jdbcClient.sql("UPDATE scheduler_leader_lease SET expires_at = now() - interval '1 second' WHERE lease_key = :key")
            .param("key", properties.key)
            .update()
    }

    @BeforeEach
    fun clearLease() {
        jdbcClient.sql("DELETE FROM scheduler_leader_lease").update()
    }

    @Test
    fun `여러 서버가 같은 순간에 얻으려 해도 한 서버만 얻는다`() {
        // given — 빈 임대에 8대가 동시에 달려든다
        val servers = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(servers)

        // when
        val results = try {
            (1..servers).map { n ->
                pool.submit(Callable { start.await(); store.claim("server-$n", Duration.ofSeconds(30)) })
            }.also { start.countDown() }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }

        // then
        assertThat(results.count { it }).isEqualTo(1)
    }

    @Test
    fun `남의 임대는 돌려주지 않는다`() {
        // given
        store.claim("leader", Duration.ofSeconds(30))

        // when
        store.release("someone-else")

        // then
        assertThat(store.claim("someone-else", Duration.ofSeconds(30))).isFalse()
    }
}
