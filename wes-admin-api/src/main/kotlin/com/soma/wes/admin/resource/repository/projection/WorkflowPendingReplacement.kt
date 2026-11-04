package com.soma.wes.admin.resource.repository.projection


internal data class WorkflowPendingReplacement(
    val id: Long,
    val storageKey: String,
    val originalFileName: String,
    val contentType: String,
)
