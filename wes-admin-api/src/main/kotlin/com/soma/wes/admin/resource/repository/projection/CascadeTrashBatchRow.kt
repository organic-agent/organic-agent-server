package com.soma.wes.admin.resource.repository.projection

import com.soma.wes.admin.resource.domain.AdminResourceType
import java.time.ZonedDateTime

data class CascadeTrashBatchRow(
    val id: Long,
    val rootType: AdminResourceType,
    val rootId: Long,
    val rootLabel: String,
    val actorAdminId: Long?,
    val actorUsername: String?,
    val status: String,
    val deletedAt: ZonedDateTime,
    val restoreUntil: ZonedDateTime,
    val restoredAt: ZonedDateTime?,
    val purgedAt: ZonedDateTime?,
    val purgeAttemptCount: Int,
    val purgeStartedAt: ZonedDateTime?,
    val failureCode: String?,
    val relationshipFacts: Map<String, Long>,
)
