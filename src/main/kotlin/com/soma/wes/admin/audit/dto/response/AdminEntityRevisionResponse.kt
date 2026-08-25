package com.soma.wes.admin.audit.dto.response

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.domain.AdminEntityRevision
import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import tools.jackson.databind.JsonNode
import java.time.ZonedDateTime

data class AdminEntityRevisionResponse(
    val id: Long,
    val targetType: AdminAuditTargetType,
    val targetId: String,
    val revisionNumber: Long,
    val operation: AdminAuditAction,
    val before: JsonNode?,
    val after: JsonNode?,
    val expiresAt: ZonedDateTime,
    val createdAt: ZonedDateTime?,
) {
    companion object {
        fun from(revision: AdminEntityRevision, codec: AdminAuditSnapshotCodec): AdminEntityRevisionResponse =
            AdminEntityRevisionResponse(
                id = revision.requiredId,
                targetType = revision.targetType,
                targetId = revision.targetId,
                revisionNumber = revision.revisionNumber,
                operation = revision.operation,
                before = codec.decode(revision.beforeSnapshot),
                after = codec.decode(revision.afterSnapshot),
                expiresAt = revision.expiresAt,
                createdAt = revision.createdAt,
            )
    }
}
