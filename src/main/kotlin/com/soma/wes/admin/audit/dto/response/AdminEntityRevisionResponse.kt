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
    val snapshotSchemaVersion: Int,
    val targetVersion: Long?,
    val before: JsonNode?,
    val after: JsonNode?,
    val expiresAt: ZonedDateTime,
    val restorable: Boolean,
    val createdAt: ZonedDateTime?,
) {
    companion object {
        fun from(
            revision: AdminEntityRevision,
            codec: AdminAuditSnapshotCodec,
            restorable: Boolean,
        ): AdminEntityRevisionResponse =
            AdminEntityRevisionResponse(
                id = revision.requiredId,
                targetType = revision.targetType,
                targetId = revision.targetId,
                revisionNumber = revision.revisionNumber,
                operation = revision.operation,
                snapshotSchemaVersion = revision.snapshotSchemaVersion,
                targetVersion = revision.targetVersion,
                before = codec.decodePermanent(revision.beforeSnapshot),
                after = codec.decodePermanent(revision.afterSnapshot),
                expiresAt = revision.restoreExpiresAt,
                restorable = restorable,
                createdAt = revision.createdAt,
            )
    }
}
