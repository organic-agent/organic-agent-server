package com.soma.wes.admin.resource.dto

import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminResourceType
import java.time.ZonedDateTime

data class AdminChildTrashResultDto(
    val trashId: Long,
    val resourceType: AdminChildTrashType,
    val resourceId: Long,
    val parentType: AdminResourceType,
    val parentId: Long,
    val status: String,
    val deletedAt: ZonedDateTime,
    val restoreUntil: ZonedDateTime,
    val purgeEligibleAt: ZonedDateTime,
    val restoreWindowDays: Long,
    val restorable: Boolean,
) {
    fun workflowDetails(): Map<String, Any?> = linkedMapOf(
        "childTrashId" to trashId,
        "resourceType" to resourceType.name,
        "resourceId" to resourceId,
        "parentType" to parentType.name,
        "parentId" to parentId,
        "trashStatus" to status,
        "deletedAt" to deletedAt,
        "restoreUntil" to restoreUntil,
        "purgeEligibleAt" to purgeEligibleAt,
        "restoreWindowDays" to restoreWindowDays,
        "restorable" to restorable,
    )
}
