package com.soma.wes.admin.resource.repository.projection

import com.soma.wes.admin.resource.domain.AdminResourceType

data class ProcessingExecutionJob(
    val id: Long,
    val jobType: String,
    val targetType: AdminResourceType,
    val targetId: Long,
    val revisionId: Long?,
    val payload: Map<String, Any?>,
    val attemptCount: Int,
    val actorAdminId: Long?,
    val reason: String,
)
