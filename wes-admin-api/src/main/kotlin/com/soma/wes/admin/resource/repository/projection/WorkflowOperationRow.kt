package com.soma.wes.admin.resource.repository.projection


data class WorkflowOperationRow(
    val id: Long,
    val action: String,
    val status: String,
    val targetType: String?,
    val targetId: String?,
    val attemptCount: Int,
)
