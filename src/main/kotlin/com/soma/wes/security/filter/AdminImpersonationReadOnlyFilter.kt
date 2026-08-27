package com.soma.wes.security.filter

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.impersonation.repository.AdminImpersonationRepository
import com.soma.wes.admin.impersonation.service.AdminImpersonationService
import com.soma.wes.security.exception.CustomAccessDeniedHandler
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class AdminImpersonationReadOnlyFilter(
    private val service: AdminImpersonationService,
    private val accessDeniedHandler: CustomAccessDeniedHandler,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val actor = SecurityContextHolder.getContext().authentication?.principal as? AdminLoginUser
            ?: return filterChain.doFilter(request, response)
        val session = service.findCurrent(actor.adminSessionId)
            ?: return filterChain.doFilter(request, response)

        if (request.method !in SAFE_METHODS && !isAllowedWrite(request, session)) {
            val businessError = AdminException(AdminErrorCode.IMPERSONATION_READ_ONLY)
            accessDeniedHandler.handle(
                request,
                response,
                AccessDeniedException(AdminErrorCode.IMPERSONATION_READ_ONLY.message, businessError),
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun isAllowedWrite(
        request: HttpServletRequest,
        session: AdminImpersonationRepository.Session,
    ): Boolean =
        (request.method == "DELETE" && (
            request.requestURI == "/internal/admin/v1/impersonations/current" ||
                request.requestURI == "/internal/admin/v1/impersonations/${session.id}"
            )) ||
            (request.method == "POST" && request.requestURI == "/internal/admin/v1/auth/logout")

    companion object {
        /** 하위 호환용 상수일 뿐, 보안 판단은 서버측 로그인 세션 상태로만 한다. */
        const val HEADER_NAME = "X-WES-Admin-Impersonation"
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
