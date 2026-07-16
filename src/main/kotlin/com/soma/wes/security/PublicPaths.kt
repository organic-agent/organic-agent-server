package com.soma.wes.security

/**
 * 인증 없이 접근할 수 있는 경로.
 *
 * [com.soma.wes.security.config.SecurityConfig]의 인가 규칙과
 * [com.soma.wes.security.filter.JwtAuthFilter]의 필터 제외 대상이 같은 목록을 보게 해서,
 * 한쪽만 수정되어 규칙이 어긋나는 일을 막는다.
 */
object PublicPaths {

    val PATTERNS: List<String> = listOf(
        "/actuator/**",
        // `/swagger-ui.html`은 `/swagger-ui/index.html`로 리다이렉트되는 진입점이라
        // `/swagger-ui/**`에 걸리지 않는다. 따로 열어줘야 한다.
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/v3/api-docs/**",
        "/api/v1/oauth/**",
        // provider가 로그인 후 브라우저를 되돌려 보내는 redirect-uri.
        // 아직 인증되지 않은 상태로 들어오므로 열어둬야 한다.
        "/login/oauth2/code/**",
        "/api/v1/auth/reissue",
    )
}
