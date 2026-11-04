package com.soma.wes.admin.resource.repository.projection

import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminResourceType
import java.time.ZonedDateTime

data class ChildTrashRow(
    val id: Long,
    val type: AdminChildTrashType,
    val resourceId: Long,
    val parentType: AdminResourceType,
    val parentId: Long,
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
)
