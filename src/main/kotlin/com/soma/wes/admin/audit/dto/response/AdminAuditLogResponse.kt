package com.soma.wes.admin.audit.dto.response

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditLog
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import java.time.ZonedDateTime

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
    val occurredAt: ZonedDateTime?,
) {
    companion object {
        fun from(audit: AdminAuditLog): AdminAuditLogResponse =
            AdminAuditLogResponse(
                id = audit.requiredId,
                action = audit.action,
                outcome = audit.outcome,
                actorAdminId = audit.actorAdminId,
                actorUsername = audit.actorUsernameSnapshot,
                targetType = audit.targetType,
                targetId = audit.targetId,
                targetLabel = audit.targetLabel,
                sourceAddress = audit.sourceAddress,
                reason = audit.reason,
                changedFields = audit.changedFields,
                revisionNumber = audit.revisionNumber,
                occurredAt = audit.createdAt,
            )
    }
}
