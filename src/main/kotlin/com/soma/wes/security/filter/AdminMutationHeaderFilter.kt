package com.soma.wes.security.filter

import com.soma.wes.security.exception.CustomAccessDeniedHandler
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.filter.OncePerRequestFilter

class AdminMutationHeaderFilter(
    private val accessDeniedHandler: CustomAccessDeniedHandler,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method in SAFE_METHODS || request.getHeader(HEADER_NAME) == HEADER_VALUE) {
            filterChain.doFilter(request, response)
            return
        }

        accessDeniedHandler.handle(request, response, AccessDeniedException("관리자 요청 헤더가 없습니다."))
    }

    companion object {
        const val HEADER_NAME = "X-WES-Admin-Request"
        const val HEADER_VALUE = "1"

        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
