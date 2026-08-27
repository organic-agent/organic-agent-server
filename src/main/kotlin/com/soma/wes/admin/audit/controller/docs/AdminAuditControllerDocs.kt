package com.soma.wes.admin.audit.controller.docs

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.dto.request.AdminRevisionRestoreRequest
import com.soma.wes.admin.audit.dto.response.AdminAuditLogDetailResponse
import com.soma.wes.admin.audit.dto.response.AdminAuditLogResponse
import com.soma.wes.admin.audit.dto.response.AdminEntityRevisionResponse
import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.global.page.PageResponse
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import java.time.ZonedDateTime
import java.util.UUID

interface AdminAuditControllerDocs {

    @Operation(summary = "관리자 감사 로그 검색")
    fun search(
        loginUser: AdminLoginUser,
        actorAdminId: Long?,
        actorUsername: String?,
        targetType: AdminAuditTargetType?,
        targetId: String?,
        action: AdminAuditAction?,
        outcome: AdminAuditOutcome?,
        correlationId: String?,
        impersonationSessionId: UUID?,
        from: ZonedDateTime?,
        to: ZonedDateTime?,
        page: Int,
        size: Int,
    ): ResponseEntity<PageResponse<AdminAuditLogResponse>>

    @Operation(summary = "관리자 감사 로그 상세 및 변경 전후 리비전 조회")
    fun getDetail(loginUser: AdminLoginUser, auditLogId: Long): ResponseEntity<AdminAuditLogDetailResponse>

    @Operation(summary = "대상 엔티티의 7일 리비전 목록 조회")
    fun getRevisions(
        loginUser: AdminLoginUser,
        targetType: AdminAuditTargetType,
        targetId: String,
    ): ResponseEntity<List<AdminEntityRevisionResponse>>

    @Operation(summary = "7일 이내 리비전 상태로 복원")
    fun restoreRevision(
        loginUser: AdminLoginUser,
        revisionId: Long,
        request: AdminRevisionRestoreRequest,
        servletRequest: jakarta.servlet.http.HttpServletRequest,
    ): ResponseEntity<AdminAuditLogDetailResponse>
}
