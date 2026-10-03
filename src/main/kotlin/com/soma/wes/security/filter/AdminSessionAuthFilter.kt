package com.soma.wes.security.filter

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.exception.AdminAuthenticationException
import com.soma.wes.admin.service.AdminSessionService
import com.soma.wes.admin.support.AdminSessionCookie
import com.soma.wes.global.filter.HttpLoggingFilter
import com.soma.wes.global.logging.LogContext
import com.soma.wes.security.exception.CustomAuthenticationEntryPoint
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class AdminSessionAuthFilter(
    private val adminSessionService: AdminSessionService,
    private val authenticationEntryPoint: CustomAuthenticationEntryPoint,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawToken = request.cookies
            ?.firstOrNull { it.name == AdminSessionCookie.NAME }
            ?.value

        if (rawToken == null) {
            filterChain.doFilter(request, response)
            return
        }

        try {
            val authentication = adminSessionService.authenticate(rawToken)
            SecurityContextHolder.getContext().authentication = authentication
            val principal = authentication.principal as AdminLoginUser
            request.setAttribute(HttpLoggingFilter.USER_ID_ATTRIBUTE, principal.id)
            // 요청 로그 줄 앞의 userId= 키로 관리자 번호를 찍는다. 비우는 것은 HttpLoggingFilter 다.
            MDC.put(LogContext.USER_ID, principal.id.toString())
        } catch (e: AdminAuthenticationException) {
            SecurityContextHolder.clearContext()
            authenticationEntryPoint.commence(request, response, e)
            return
        }

        filterChain.doFilter(request, response)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI == LOGIN_PATH

    companion object {
        private const val LOGIN_PATH = "/internal/admin/v1/auth/login"
    }
}
