package com.soma.wes.admin.resource.repository.projection

import com.soma.wes.admin.resource.domain.AdminResourceType

data class WorkflowProcessingJob(
    val id: Long,
    val jobType: String,
    val status: String,
    val targetType: AdminResourceType,
    val targetId: Long,
    val revisionId: Long?,
    val payload: Map<String, Any?>,
    val attemptCount: Int,
    val failureCode: String?,
)
