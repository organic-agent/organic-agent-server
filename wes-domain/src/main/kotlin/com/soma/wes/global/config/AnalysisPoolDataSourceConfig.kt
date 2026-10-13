package com.soma.wes.global.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * 연결 풀을 사용자 요청용과 분석 쪽(주기 작업·폴더 단계)용으로 나눈다(#286 (a), ADR 0001).
 *
 * 공용 풀 10개 하나일 때 사용자 200명이 풀을 다 쥐자(대기 최대 194) 연결 0~1개면 되는 분석 스윕도 그 뒤에 섰다 — 임베더 배정이
 * 분당 130~140 → 14~46으로 떨어지고 업로드 → AI 폴더가 16:28로 밀렸다(R-3-a). 분석 쪽에 작은 풀을 따로 두면 사용자 트래픽과
 * 상관없이 연결을 얻는다.
 *
 * DataSource를 직접 정의하면 Boot의 풀 자동 구성이 물러나면서 접속 정보 빈([JdbcConnectionDetails])도 만들지 않는다. 그래서
 * 접속 정보는 있으면(Testcontainers 서비스 연결) 그것을, 없으면 `spring.datasource.*`를 쓴다. 풀 세부 설정
 * (`spring.datasource.hikari.*`, 예: `socketTimeout`)은 사용자 풀에 묶고, 분석 풀은 접속 정보·시간 제한·세션 설정(스키마·
 * 초기화 SQL·격리 수준 등)·연결 속성을 하나씩 옮겨 받는다. 두 풀은 크기와 이름만 달라야 어느 풀로 가든 쿼리 결과가 같다 —
 * `HikariConfig`에 설정을 새로 쓰게 되면 여기도 옮긴다. `HikariConfig.copyStateTo`를 쓰지 않는 이유는 연결 속성 객체를 공유하게
 * 만들어, 분석 풀 이름을 넣으면 사용자 풀 이름까지 바뀌기 때문이다.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.datasource.analysis-pool", name = ["enabled"], havingValue = "true")
class AnalysisPoolDataSourceConfig {

    companion object {
        /** 풀 이름이자 `pg_stat_activity.application_name` — DB 쪽에서도 어느 풀의 연결인지 보이게 한다. */
        const val USER_POOL = "wes-api-user"
        const val ANALYSIS_POOL = "wes-api-analysis"

        /** PostgreSQL JDBC 드라이버의 접속 속성 이름. */
        private const val APPLICATION_NAME = "ApplicationName"
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    fun userDataSource(
        connectionDetails: ObjectProvider<JdbcConnectionDetails>,
        dataSourceProperties: DataSourceProperties,
    ): HikariDataSource = builderOf(connectionDetails.ifAvailable, dataSourceProperties).build().apply {
        poolName = USER_POOL
        addDataSourceProperty(APPLICATION_NAME, USER_POOL)
    }

    @Bean
    fun analysisDataSource(
        userDataSource: HikariDataSource,
        properties: AnalysisPoolProperties,
    ): HikariDataSource = HikariDataSource().apply {
        jdbcUrl = userDataSource.jdbcUrl
        username = userDataSource.username
        password = userDataSource.password
        driverClassName = userDataSource.driverClassName

        connectionTimeout = userDataSource.connectionTimeout
        validationTimeout = userDataSource.validationTimeout
        idleTimeout = userDataSource.idleTimeout
        maxLifetime = userDataSource.maxLifetime
        keepaliveTime = userDataSource.keepaliveTime
        leakDetectionThreshold = userDataSource.leakDetectionThreshold
        initializationFailTimeout = userDataSource.initializationFailTimeout

        catalog = userDataSource.catalog
        schema = userDataSource.schema
        connectionInitSql = userDataSource.connectionInitSql
        connectionTestQuery = userDataSource.connectionTestQuery
        transactionIsolation = userDataSource.transactionIsolation
        isAutoCommit = userDataSource.isAutoCommit
        isReadOnly = userDataSource.isReadOnly
        isIsolateInternalQueries = userDataSource.isIsolateInternalQueries
        for ((key, value) in userDataSource.dataSourceProperties) addDataSourceProperty(key.toString(), value)

        poolName = ANALYSIS_POOL
        maximumPoolSize = properties.maxSize
        minimumIdle = properties.minIdle
        addDataSourceProperty(APPLICATION_NAME, ANALYSIS_POOL)
    }

    @Bean
    @Primary
    fun dataSource(
        userDataSource: HikariDataSource,
        analysisDataSource: HikariDataSource,
        route: AnalysisPoolRoute,
    ): AnalysisRoutingDataSource = AnalysisRoutingDataSource(
        userDataSource = userDataSource,
        analysisDataSource = analysisDataSource,
        route = route,
    )

    /** `@Scheduled` 작업은 전부 분석 풀을 쓴다 — 분석 스윕 말고는 매시 정리 작업뿐이라 따로 가르지 않는다. */
    @Bean
    fun analysisPoolSchedulerCustomizer(route: AnalysisPoolRoute): ThreadPoolTaskSchedulerCustomizer =
        ThreadPoolTaskSchedulerCustomizer { it.setTaskDecorator(route.taskDecorator) }

    private fun builderOf(
        connectionDetails: JdbcConnectionDetails?,
        dataSourceProperties: DataSourceProperties,
    ): DataSourceBuilder<HikariDataSource> {
        if (connectionDetails == null) {
            return dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource::class.java)
        }
        return DataSourceBuilder.create(dataSourceProperties.classLoader)
            .type(HikariDataSource::class.java)
            .driverClassName(connectionDetails.driverClassName)
            .url(connectionDetails.jdbcUrl)
            .username(connectionDetails.username)
            .password(connectionDetails.password)
    }

}
