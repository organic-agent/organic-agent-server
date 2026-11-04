package com.soma.wes.admin.resource.dto


internal data class WorkflowExecutionDto(
    val status: String = "COMPLETED",
    val details: Map<String, Any?> = emptyMap(),
)
