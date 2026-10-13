package com.soma.wes.admin.audit.dto.response

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditLog
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import com.soma.wes.admin.audit.support.AdminAuditSanitizer
import java.time.ZonedDateTime
import java.util.UUID

data class AdminAuditLogResponse(
    val id: Long,
    val action: AdminAuditAction,
    val outcome: AdminAuditOutcome,
    val actorAdminId: Long?,
    val actorUsername: String?,
    val targetType: AdminAuditTargetType?,
    val targetId: String?,
    val targetLabel: String?,
    val sourceAddress: String?,
    val reason: String?,
    val changedFields: List<String>,
    val revisionNumber: Long?,
    val correlationId: String?,
    val impersonationSessionId: UUID?,
    val occurredAt: ZonedDateTime?,
) {
    companion object {
        fun from(
            audit: AdminAuditLog,
            sanitizer: AdminAuditSanitizer,
            snapshotCodec: AdminAuditSnapshotCodec,
        ): AdminAuditLogResponse =
            AdminAuditLogResponse(
                id = audit.requiredId,
                action = audit.action,
                outcome = audit.outcome,
                actorAdminId = audit.actorAdminId,
                actorUsername = sanitizer.canonicalActorLabel(audit.actorAdminId),
                targetType = audit.targetType,
                targetId = audit.targetId,
                targetLabel = sanitizer.canonicalTargetLabel(audit.targetType, audit.targetId, audit.targetLabel),
                // 신규 정책 이전 행도 API에서 IP나 자유 입력 사유 원문을 다시 노출하지 않는다.
                sourceAddress = null,
                reason = sanitizer.sanitizeStoredReason(audit.reason),
                // 신규 exact-key 정책 이전 행도 자유 입력 field명을 API로 다시 노출하지 않는다.
                changedFields = snapshotCodec.sanitizeChangedFields(audit.changedFields),
                revisionNumber = audit.revisionNumber,
                correlationId = audit.correlationId,
                impersonationSessionId = audit.impersonationSessionId,
                occurredAt = audit.createdAt,
            )
    }
}
