package com.soma.wes.admin.resource.dto.response

import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminResourceType
import java.time.ZonedDateTime

data class AdminTrashBatchResponse(
    val id: Long,
    val rootType: AdminResourceType,
    val rootId: Long,
    val rootLabel: String,
    val status: String,
    val actorUsername: String?,
    val deletedAt: ZonedDateTime,
    val restoreUntil: ZonedDateTime,
    val purgeEligibleAt: ZonedDateTime,
    val restoreWindowDays: Long,
    val restorable: Boolean,
    val restoredAt: ZonedDateTime?,
    val purgedAt: ZonedDateTime?,
    val purgeAttemptCount: Int,
    val failureCode: String?,
    val affectedCounts: Map<String, Long>,
    val relationshipFacts: Map<String, Long>,
    val entries: List<AdminTrashEntryResponse>,
)

data class AdminTrashEntryResponse(
    val resourceType: String,
    val resourceId: Long,
    val root: Boolean,
    val relationPath: String,
)

data class AdminChildTrashResponse(
    val id: Long,
    val resourceType: AdminChildTrashType,
    val resourceId: Long,
    val parentType: AdminResourceType,
    val parentId: Long,
    val status: String,
    val actorUsername: String?,
    val deletedAt: ZonedDateTime,
    val restoreUntil: ZonedDateTime,
    val purgeEligibleAt: ZonedDateTime,
    val restoreWindowDays: Long,
    val restorable: Boolean,
    val restoredAt: ZonedDateTime?,
    val purgedAt: ZonedDateTime?,
    val purgeAttemptCount: Int,
    val failureCode: String?,
)
