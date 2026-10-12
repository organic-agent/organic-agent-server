package com.soma.wes.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 분석 쪽(주기 작업·폴더 단계) 전용 연결 풀 설정(#286 (a)). 켜면 [AnalysisPoolDataSourceConfig]가 사용자 풀과 분석 풀을 나눈다.
 *
 * 공용 풀 하나일 때 사용자 요청이 풀을 다 쥐면 분석 스윕도 그 줄 뒤에 서서 업로드 → AI 폴더가 10:17 → 16:28로 밀렸다(R-3-a).
 * wes-api 런타임 설정만 켠다 — 관리자 앱(wes-admin-api)은 분석 스윕을 돌리지 않는다.
 */
@ConfigurationProperties(prefix = "app.datasource.analysis-pool")
data class AnalysisPoolProperties(
    val enabled: Boolean = false,
    /**
     * 분석 쪽이 동시에 쥐는 연결 상한. 폴더 단계가 categorize 결과를 최대 `app.analysis.folder-parallelism`(5)개 동시에
     * 물질화하고, 5초 스윕·1초 폴더 호출 스레드가 더해진다 — 그보다 작으면 폴더 만들기가 줄을 선다.
     */
    val maxSize: Int = 7,
    /** 평소 열어 두는 연결 수. 업로드가 없을 때도 스윕이 5초·1초마다 쓰는 몫이다. 이보다 많이 연 연결은 쓰지 않으면 닫힌다. */
    val minIdle: Int = 1,
)
