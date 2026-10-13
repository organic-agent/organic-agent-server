package com.soma.wes.security.filter

import com.soma.wes.admin.audit.service.AdminResourceReadAuditService
import com.soma.wes.admin.domain.AdminLoginUser
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

/** 관리자 GET 응답이 확정된 뒤 성공·실패를 메타데이터만으로 영구 감사한다. */
class AdminReadAuditFilter(
    private val readAuditService: AdminResourceReadAuditService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val bufferedResponse = ContentCachingResponseWrapper(response)
        var thrown: Throwable? = null
        try {
            filterChain.doFilter(request, bufferedResponse)
        } catch (error: Throwable) {
            thrown = error
        }

        val status = if (thrown != null && bufferedResponse.status < 400) 500 else bufferedResponse.status
        try {
            if (status >= 400 || !readAuditService.hasDedicatedSuccessAudit(request.requestURI)) {
                record(request, status)
            }
        } catch (auditError: Throwable) {
            // 성공 본문은 감사 행이 확정될 때까지 client로 flush하지 않는다. 감사 DB가 실패하면
            // 조회도 성공으로 반환하지 않아 "200인데 감사 행 없음" 상태를 만들지 않는다.
            if (!response.isCommitted) {
                bufferedResponse.resetBuffer()
                response.resetBuffer()
                response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            }
            thrown?.let(auditError::addSuppressed)
            throw auditError
        }

        thrown?.let { throw it }
        bufferedResponse.copyBodyToResponse()
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "GET" || !request.requestURI.startsWith(ADMIN_API_PREFIX)

    private fun record(request: HttpServletRequest, status: Int) {
        val actor = SecurityContextHolder.getContext().authentication?.principal as? AdminLoginUser
        readAuditService.recordRequest(actor, request.remoteAddr, request.requestURI, status)
    }

    companion object {
        private const val ADMIN_API_PREFIX = "/internal/admin/v1/"
    }
}
