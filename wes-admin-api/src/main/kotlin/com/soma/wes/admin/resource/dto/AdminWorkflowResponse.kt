package com.soma.wes.admin.resource.dto

import com.soma.wes.admin.resource.domain.AdminResourceType

data class AdminWorkflowResponse(
    val action: AdminWorkflowAction,
    val targetType: AdminResourceType,
    val targetId: Long,
    val idempotencyKey: String,
    val status: String,
    val replayed: Boolean = false,
    val details: Map<String, Any?> = emptyMap(),
)
