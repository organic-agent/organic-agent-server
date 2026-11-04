package com.soma.wes.admin.resource.repository.projection


internal data class WorkflowAiCandidate(
    val photoId: Long,
    val embedding: DoubleArray,
    val rating: Double,
    val pixels: Long,
    val quality: Double = 0.0,
)
