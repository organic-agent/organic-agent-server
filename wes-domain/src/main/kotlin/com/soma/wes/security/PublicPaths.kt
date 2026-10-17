package com.soma.wes.security

/**
 * 인증 없이 접근할 수 있는 경로.
 */
object PublicPaths {

    val PATTERNS: List<String> = listOf(
        "/actuator/**",
        // 헬스 프로브(application-variable.yml `management.endpoint.health.probes`) — ALB·배포 스크립트가 토큰 없이 부른다.
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
