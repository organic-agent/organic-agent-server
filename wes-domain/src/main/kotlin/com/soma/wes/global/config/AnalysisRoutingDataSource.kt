package com.soma.wes.global.config

import javax.sql.DataSource
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource

/**
 * 연결을 꺼내는 순간의 스레드 표시([AnalysisPoolRoute])로 사용자 풀과 분석 풀 중 하나를 고른다(#286 (a)).
 *
 * 트랜잭션은 시작할 때 연결 하나를 잡아 끝까지 쓰므로, 한 트랜잭션 안에서 풀이 바뀌지는 않는다.
 */
class AnalysisRoutingDataSource(
    userDataSource: DataSource,
    analysisDataSource: DataSource,
    private val route: AnalysisPoolRoute,
) : AbstractRoutingDataSource() {

    companion object {
        private const val USER = "user"
        private const val ANALYSIS = "analysis"
    }

    init {
        setTargetDataSources(mapOf(USER to userDataSource, ANALYSIS to analysisDataSource))
        setDefaultTargetDataSource(userDataSource)
    }

    override fun determineCurrentLookupKey(): String = if (route.isAnalysis()) ANALYSIS else USER
}
