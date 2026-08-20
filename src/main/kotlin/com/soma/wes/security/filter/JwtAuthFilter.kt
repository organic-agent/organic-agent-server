package com.soma.wes.security.filter

import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.global.filter.HttpLoggingFilter
import com.soma.wes.security.PublicPaths
import com.soma.wes.security.exception.CustomAuthenticationEntryPoint
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter


class JwtAuthFilter(
    private val authTokenProvider: AuthTokenProvider,
    private val authenticationEntryPoint: CustomAuthenticationEntryPoint,
) : OncePerRequestFilter() {

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }

    private val pathMatcher = AntPathMatcher()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        logger.debug("JwtAuthFilter 실행: ${request.method} ${request.requestURI} (dispatcher=${request.dispatcherType})")

        val token = extractBearerToken(request)

        if (token == null) {
            // 인증이 필요한 경로라면 인가 단계에서 401이 나간다. 여기서 막지 않는다.
            filterChain.doFilter(request, response)
            return
        }

        try {
            val authentication = authTokenProvider.getAuthUser(token)
            SecurityContextHolder.getContext().authentication = authentication

            // HttpLoggingFilter가 RESPONSE 줄에 찍을 값. 그 필터는 시큐리티 체인 밖에 있어
            // 응답 시점에는 SecurityContext가 이미 비워져 있으므로 request에 실어 넘긴다.
            val loginUser = authentication.principal as? LoginUser
            request.setAttribute(HttpLoggingFilter.USER_ID_ATTRIBUTE, loginUser?.id)
        } catch (e: AuthenticationException) {
            // 토큰이 붙어 있는데 유효하지 않다면 요청을 통과시키지 않고 즉시 401을 응답한다.
            SecurityContextHolder.clearContext()
            authenticationEntryPoint.commence(request, response, e)
            return
        }

        filterChain.doFilter(request, response)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        PublicPaths.PATTERNS.any { pathMatcher.match(it, request.requestURI) }

    /**
     * `Authorization: Bearer {token}` 헤더에서 토큰만 떼어낸다.
     * 헤더가 없거나 Bearer 스킴이 아니면 null을 반환한다(= 인증을 시도하지 않는다).
     */
    private fun extractBearerToken(request: HttpServletRequest): AccessToken? =
        request.getHeader(AUTHORIZATION_HEADER)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf { it.isNotBlank() }
            ?.let(::AccessToken)
}
