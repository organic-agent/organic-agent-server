package com.soma.wes.security.filter

import com.soma.wes.admin.audit.service.AdminMutationFailureAuditService
import com.soma.wes.admin.audit.support.AdminMutationAuditContext
import com.soma.wes.global.filter.HttpLoggingFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

/** 실패 응답이 확정된 뒤 별도 트랜잭션으로 관리자 변경 실패를 영구 감사한다. */
class AdminMutationFailureAuditFilter(
    private val failureAuditService: AdminMutationFailureAuditService,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        var thrown = false
        try {
            filterChain.doFilter(request, response)
        } catch (error: Throwable) {
            thrown = true
            throw error
        } finally {
            val status = if (thrown && response.status < 400) 500 else response.status
            if (status >= 400 && request.getAttribute(AdminMutationAuditContext.FAILURE_AUDITED_ATTRIBUTE) != true) {
                if (request.getAttribute(AdminMutationAuditContext.MUTATION_COMMITTED_ATTRIBUTE) == true) {
                    recordResponseFailure(request, status)
                } else {
                    recordFailure(request, status)
                }
            }
        }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method in SAFE_METHODS || request.requestURI == LOGIN_PATH

    private fun recordFailure(request: HttpServletRequest, status: Int) {
        runCatching {
            failureAuditService.record(
                actorAdminId = request.getAttribute(HttpLoggingFilter.USER_ID_ATTRIBUTE) as? Long,
                method = request.method,
                requestUri = request.requestURI,
                status = status,
                sourceAddress = request.remoteAddr,
                correlationId = MDC.get(HttpLoggingFilter.TRACE_ID_KEY),
            )
        }.onFailure { error ->
            log.error("관리자 변경 실패 감사 기록을 저장하지 못했다", error)
        }
    }

    private fun recordResponseFailure(request: HttpServletRequest, status: Int) {
        runCatching {
            failureAuditService.recordResponseFailure(
                actorAdminId = request.getAttribute(HttpLoggingFilter.USER_ID_ATTRIBUTE) as? Long,
                method = request.method,
                requestUri = request.requestURI,
                status = status,
                sourceAddress = request.remoteAddr,
                correlationId = MDC.get(HttpLoggingFilter.TRACE_ID_KEY),
            )
        }.onFailure { error ->
            log.error("커밋 후 관리자 응답 실패 감사를 저장하지 못했다", error)
        }
    }

    companion object {
        private const val LOGIN_PATH = "/internal/admin/v1/auth/login"
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
