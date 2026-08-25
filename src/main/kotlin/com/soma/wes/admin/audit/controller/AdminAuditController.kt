package com.soma.wes.admin.audit.controller

import com.soma.wes.admin.audit.controller.docs.AdminAuditControllerDocs
import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.dto.response.AdminAuditLogDetailResponse
import com.soma.wes.admin.audit.dto.response.AdminAuditLogResponse
import com.soma.wes.admin.audit.dto.response.AdminEntityRevisionResponse
import com.soma.wes.admin.audit.service.AdminAuditQueryService
import com.soma.wes.admin.audit.service.AdminRevisionRestoreService
import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.dto.request.AdminReasonRequest
import com.soma.wes.global.page.PageResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.ZonedDateTime

@RestController
@RequestMapping("/internal/admin/v1/audit-logs")
class AdminAuditController(
    private val queryService: AdminAuditQueryService,
    private val restoreService: AdminRevisionRestoreService,
) : AdminAuditControllerDocs {

    @GetMapping
    override fun search(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @RequestParam(required = false) actorAdminId: Long?,
        @RequestParam(required = false) actorUsername: String?,
        @RequestParam(required = false) targetType: AdminAuditTargetType?,
        @RequestParam(required = false) targetId: String?,
        @RequestParam(required = false) action: AdminAuditAction?,
        @RequestParam(required = false) outcome: AdminAuditOutcome?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: ZonedDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: ZonedDateTime?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<PageResponse<AdminAuditLogResponse>> =
        ResponseEntity.ok(
            queryService.search(
                actorAdminId = actorAdminId,
                actorUsername = actorUsername,
                targetType = targetType,
                targetId = targetId,
                action = action,
                outcome = outcome,
                from = from,
                to = to,
                page = page,
                size = size,
            ),
        )

    @GetMapping("/{auditLogId}")
    override fun getDetail(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable auditLogId: Long,
    ): ResponseEntity<AdminAuditLogDetailResponse> =
        ResponseEntity.ok(queryService.getDetail(auditLogId))

    @GetMapping("/targets/{targetType}/{targetId}/revisions")
    override fun getRevisions(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable targetType: AdminAuditTargetType,
        @PathVariable targetId: String,
    ): ResponseEntity<List<AdminEntityRevisionResponse>> =
        ResponseEntity.ok(queryService.getRevisions(targetType, targetId))

    @PostMapping("/revisions/{revisionId}/restore")
    override fun restoreRevision(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable revisionId: Long,
        @Valid @RequestBody request: AdminReasonRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminAuditLogDetailResponse> {
        val auditLogId = restoreService.restore(
            actorAdminId = loginUser.id,
            revisionId = revisionId,
            request = request,
            sourceAddress = servletRequest.remoteAddr,
        )
        return ResponseEntity.ok(queryService.getDetail(auditLogId))
    }
}
