package com.soma.wes.global.config

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

/**
 * 분석 풀이 사용자 풀의 연결 조건을 빠짐없이 물려받는지(#286 (a)). 운영 설정에는 아직 없는 값(스키마·초기화 SQL 등)까지 채워 본다 —
 * 누가 `spring.datasource.hikari.*`에 세션 설정을 더해도 분석 풀만 다른 조건으로 붙지 않아야 한다. 풀은 만들기만 하고 열지 않는다.
 */
class AnalysisPoolDataSourceConfigUnitTest {

    private val config = AnalysisPoolDataSourceConfig()

    @Test
    fun `분석 풀은 크기와 이름 말고 사용자 풀의 연결 조건을 그대로 쓴다`() {
        // given — 기본값과 다른 값을 고루 채운 사용자 풀
        val user = HikariDataSource().apply {
            jdbcUrl = "jdbc:postgresql://db.example:5432/wes"
            username = "wes"
            password = "secret"
            driverClassName = "org.postgresql.Driver"
            connectionTimeout = 12_000
            validationTimeout = 3_000
            idleTimeout = 300_000
            maxLifetime = 900_000
            keepaliveTime = 60_000
            leakDetectionThreshold = 20_000
            initializationFailTimeout = -1
            catalog = "wes"
            schema = "app"
            connectionInitSql = "SET statement_timeout = '30s'"
            connectionTestQuery = "SELECT 1"
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            isAutoCommit = false
            isReadOnly = false
            isIsolateInternalQueries = true
            addDataSourceProperty("socketTimeout", "120")
            addDataSourceProperty("ApplicationName", AnalysisPoolDataSourceConfig.USER_POOL)
        }

        // when
        val analysis = config.analysisDataSource(user, AnalysisPoolProperties(enabled = true, maxSize = 7, minIdle = 1))

        // then
        assertSoftly { softly ->
            softly.assertThat(analysis.jdbcUrl).isEqualTo(user.jdbcUrl)
            softly.assertThat(analysis.username).isEqualTo(user.username)
            softly.assertThat(analysis.password).isEqualTo(user.password)
            softly.assertThat(analysis.driverClassName).isEqualTo(user.driverClassName)
            softly.assertThat(analysis.connectionTimeout).isEqualTo(user.connectionTimeout)
            softly.assertThat(analysis.validationTimeout).isEqualTo(user.validationTimeout)
            softly.assertThat(analysis.idleTimeout).isEqualTo(user.idleTimeout)
            softly.assertThat(analysis.maxLifetime).isEqualTo(user.maxLifetime)
            softly.assertThat(analysis.keepaliveTime).isEqualTo(user.keepaliveTime)
            softly.assertThat(analysis.leakDetectionThreshold).isEqualTo(user.leakDetectionThreshold)
            softly.assertThat(analysis.initializationFailTimeout).isEqualTo(user.initializationFailTimeout)
            softly.assertThat(analysis.catalog).isEqualTo(user.catalog)
            softly.assertThat(analysis.schema).isEqualTo(user.schema)
            softly.assertThat(analysis.connectionInitSql).isEqualTo(user.connectionInitSql)
            softly.assertThat(analysis.connectionTestQuery).isEqualTo(user.connectionTestQuery)
            softly.assertThat(analysis.transactionIsolation).isEqualTo(user.transactionIsolation)
            softly.assertThat(analysis.isAutoCommit).isEqualTo(user.isAutoCommit)
            softly.assertThat(analysis.isReadOnly).isEqualTo(user.isReadOnly)
            softly.assertThat(analysis.isIsolateInternalQueries).isEqualTo(user.isIsolateInternalQueries)
            softly.assertThat(analysis.dataSourceProperties.getProperty("socketTimeout")).isEqualTo("120")

            softly.assertThat(analysis.poolName).isEqualTo(AnalysisPoolDataSourceConfig.ANALYSIS_POOL)
            softly.assertThat(analysis.maximumPoolSize).isEqualTo(7)
            softly.assertThat(analysis.minimumIdle).isEqualTo(1)
            softly.assertThat(analysis.dataSourceProperties.getProperty("ApplicationName"))
                .isEqualTo(AnalysisPoolDataSourceConfig.ANALYSIS_POOL)
        }
    }

    @Test
    fun `분석 풀 이름을 붙여도 사용자 풀의 연결 이름은 그대로다`() {
        // given
        val user = HikariDataSource().apply {
            jdbcUrl = "jdbc:postgresql://db.example:5432/wes"
            addDataSourceProperty("ApplicationName", AnalysisPoolDataSourceConfig.USER_POOL)
        }

        // when
        config.analysisDataSource(user, AnalysisPoolProperties(enabled = true))

        // then
        assertSoftly { softly ->
            softly.assertThat(user.dataSourceProperties.getProperty("ApplicationName"))
                .isEqualTo(AnalysisPoolDataSourceConfig.USER_POOL)
            softly.assertThat(user.poolName).isNotEqualTo(AnalysisPoolDataSourceConfig.ANALYSIS_POOL)
        }
    }
}
