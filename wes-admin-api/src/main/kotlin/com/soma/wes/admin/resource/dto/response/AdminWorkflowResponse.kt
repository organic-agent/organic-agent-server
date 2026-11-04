package com.soma.wes.admin.resource.dto.response

import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.domain.AdminWorkflowAction

data class AdminWorkflowResponse(
    val action: AdminWorkflowAction,
    val targetType: AdminResourceType,
    val targetId: Long,
    val idempotencyKey: String,
    val status: String,
    val replayed: Boolean = false,
    val details: Map<String, Any?> = emptyMap(),
)
