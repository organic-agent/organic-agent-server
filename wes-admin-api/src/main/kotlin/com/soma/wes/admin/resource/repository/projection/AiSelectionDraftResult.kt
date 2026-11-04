package com.soma.wes.admin.resource.repository.projection


data class AiSelectionDraftResult(
    val jobId: Long,
    val status: String,
    val revisionId: Long?,
    val photoIds: List<Long>,
    val failureCode: String?,
)
