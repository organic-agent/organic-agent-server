package com.soma.wes.global.config

import com.soma.wes.support.IntegrationTest
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.TaskScheduler
import org.springframework.transaction.support.TransactionTemplate

/** 사용자 요청과 분석 쪽(주기 작업·폴더 단계)이 서로 다른 연결 풀을 쓰는지(#286 (a)). 연결의 `application_name`이 풀 이름이다. */
@IntegrationTest
class AnalysisPoolDataSourceConfigTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val route: AnalysisPoolRoute,
    private val taskScheduler: TaskScheduler,
    @Qualifier("userDataSource") private val userDataSource: HikariDataSource,
    @Qualifier("analysisDataSource") private val analysisDataSource: HikariDataSource,
) {

    @Nested
    inner class `연결을 고를 때` {

        @Test
        fun `표시 없는 스레드는 사용자 풀을 쓴다`() {
            assertThat(poolOfConnection()).isEqualTo(AnalysisPoolDataSourceConfig.USER_POOL)
        }

        @Test
        fun `분석 표시 안에서는 분석 풀을 쓴다`() {
            assertThat(route.asAnalysis { poolOfConnection() }).isEqualTo(AnalysisPoolDataSourceConfig.ANALYSIS_POOL)
        }

        @Test
        fun `분석 표시 안에서 연 트랜잭션은 끝까지 분석 풀이다`() {
            // when — 트랜잭션 안에서 두 번 묻는다
            val pools = route.asAnalysis {
                transactionTemplate.execute { listOf(poolOfConnection(), poolOfConnection()) }
            }

            // then
            assertThat(pools).containsExactly(AnalysisPoolDataSourceConfig.ANALYSIS_POOL, AnalysisPoolDataSourceConfig.ANALYSIS_POOL)
        }

        @Test
        fun `주기 작업 스케줄러의 작업은 분석 풀을 쓴다`() {
            // given
            val pool = CompletableFuture<String>()

            // when
            taskScheduler.schedule({ pool.complete(poolOfConnection()) }, Instant.now())

            // then
            assertThat(pool.get(10, TimeUnit.SECONDS)).isEqualTo(AnalysisPoolDataSourceConfig.ANALYSIS_POOL)
        }

        @Test
        fun `분석 표시는 작업이 끝나면 지워진다`() {
            // when
            route.asAnalysis { poolOfConnection() }

            // then
            assertThat(route.isAnalysis()).isFalse()
        }
    }

    @Test
    fun `분석 풀은 사용자 풀의 접속 설정을 물려받고 크기만 작다`() {
        assertSoftly { softly ->
            softly.assertThat(userDataSource.maximumPoolSize).isEqualTo(10)
            softly.assertThat(analysisDataSource.maximumPoolSize).isEqualTo(7)
            softly.assertThat(analysisDataSource.minimumIdle).isEqualTo(1)
            softly.assertThat(analysisDataSource.jdbcUrl).isEqualTo(userDataSource.jdbcUrl)
            softly.assertThat(analysisDataSource.dataSourceProperties.getProperty("socketTimeout"))
                .isEqualTo(userDataSource.dataSourceProperties.getProperty("socketTimeout"))
                .isNotNull()
        }
    }

    private fun poolOfConnection(): String? =
        jdbcTemplate.queryForObject("SELECT current_setting('application_name')", String::class.java)
}
