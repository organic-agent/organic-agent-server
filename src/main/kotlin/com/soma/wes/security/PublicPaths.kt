package com.soma.wes.security

/**
 * 인증 없이 접근할 수 있는 경로.
 */
object PublicPaths {

    val PATTERNS: List<String> = listOf(
        "/actuator/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/v3/api-docs/**",
        "/api/v1/oauth/**",
        "/login/oauth2/code/**",
        "/api/v1/auth/reissue",
        "/api/v1/collab/**",
    )
}
