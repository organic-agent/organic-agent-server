package com.soma.wes.security.filter

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.impersonation.service.AdminImpersonationService
import com.soma.wes.security.exception.CustomAccessDeniedHandler
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

class AdminImpersonationReadOnlyFilter(
    private val service: AdminImpersonationService,
    private val accessDeniedHandler: CustomAccessDeniedHandler,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawSessionId = request.getHeader(HEADER_NAME)?.takeIf { it.isNotBlank() }
            ?: return filterChain.doFilter(request, response)
        val actor = SecurityContextHolder.getContext().authentication?.principal as? AdminLoginUser
            ?: return filterChain.doFilter(request, response)
        val sessionId = runCatching { UUID.fromString(rawSessionId) }.getOrNull()

        try {
            if (sessionId == null) throw AccessDeniedException("올바르지 않은 대리보기 세션입니다.")
            service.requireActive(sessionId, actor.id)
            if (request.method !in SAFE_METHODS && !isOwnEndRequest(request, sessionId)) {
                throw AccessDeniedException("읽기 전용 대리보기에서는 쓰기 요청을 실행할 수 없습니다.")
            }
            filterChain.doFilter(request, response)
        } catch (e: AdminException) {
            accessDeniedHandler.handle(
                request,
                response,
                AccessDeniedException(e.message ?: "활성 대리보기 세션이 아닙니다.", e),
            )
        } catch (e: AccessDeniedException) {
            accessDeniedHandler.handle(request, response, e)
        }
    }

    private fun isOwnEndRequest(request: HttpServletRequest, sessionId: UUID): Boolean =
        request.method == "DELETE" && request.requestURI == "/internal/admin/v1/impersonations/$sessionId"

    companion object {
        const val HEADER_NAME = "X-WES-Admin-Impersonation"
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
