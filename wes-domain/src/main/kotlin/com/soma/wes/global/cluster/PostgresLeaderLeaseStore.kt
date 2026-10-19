package com.soma.wes.global.cluster

import com.soma.wes.global.config.AnalysisPoolRoute
import com.soma.wes.global.config.ClusterLeaderProperties
import java.time.Duration
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * 리더 임대를 `scheduler_leader_lease` 행 하나에 둔다(#336 ADR-0002, V45). 얻기·연장은 조건부 UPSERT 한 문장이라 두 서버가 동시에 불러도
 * 한 서버만 행을 돌려받는다.
 *
 * - 만료 판정은 DB 시각(`now()`)이다 — 서버 시계가 어긋나도 둘이 동시에 얻지 않는다.
 * - 연결은 분석 풀에서 꺼낸다. 사용자 요청이 몰려 사용자 풀이 차도 갱신이 밀려 멀쩡한 리더가 임대를 놓치지 않게(#286 (a)).
 */
@Component
@ConditionalOnExpression("\${app.cluster.leader.enabled:false} and '\${app.cluster.leader.store:redis}'.equalsIgnoreCase('postgres')")
class PostgresLeaderLeaseStore(
    private val jdbcClient: JdbcClient,
    private val properties: ClusterLeaderProperties,
    private val route: AnalysisPoolRoute,
) : LeaderLeaseStore {

    override fun claim(holder: String, ttl: Duration): Boolean = route.asAnalysis {
        jdbcClient.sql(
            """
            INSERT INTO scheduler_leader_lease (lease_key, holder, expires_at)
            VALUES (:key, :holder, now() + make_interval(secs => :ttlSeconds))
            ON CONFLICT (lease_key) DO UPDATE
                SET holder = EXCLUDED.holder, expires_at = EXCLUDED.expires_at
                WHERE scheduler_leader_lease.holder = EXCLUDED.holder
                   OR scheduler_leader_lease.expires_at < now()
            RETURNING holder
            """.trimIndent(),
        )
            .param("key", properties.key)
            .param("holder", holder)
            .param("ttlSeconds", ttl.toMillis() / 1000.0)
            .query(String::class.java)
            .optional()
            .isPresent
    }

    override fun release(holder: String) {
        route.asAnalysis {
            jdbcClient.sql("DELETE FROM scheduler_leader_lease WHERE lease_key = :key AND holder = :holder")
                .param("key", properties.key)
                .param("holder", holder)
                .update()
        }
    }
}
