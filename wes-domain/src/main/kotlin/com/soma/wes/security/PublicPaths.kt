package com.soma.wes.security

/**
 * 인증 없이 접근할 수 있는 경로.
 */
object PublicPaths {

    val PATTERNS: List<String> = listOf(
        "/actuator/**",
        // 헬스 프로브(application-api-runtime.yml `management.endpoint.health.probes`) — 관리 포트를 열지 않은 서버(로컬·운영 P1 전)의 본 포트 길.
        "/livez",
        "/readyz",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/v3/api-docs/**",
        "/api/v1/oauth/**",
        "/login/oauth2/code/**",
        "/api/v1/auth/reissue",
        "/api/v1/collab/**",
    )
}
