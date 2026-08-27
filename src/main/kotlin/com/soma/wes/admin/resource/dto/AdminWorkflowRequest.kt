package com.soma.wes.admin.resource.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class AdminWorkflowRequest(
    val action: AdminWorkflowAction,
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(min = 8, max = 128)
    val idempotencyKey: String,
    val confirm: Boolean,
    val fields: Map<String, Any?> = emptyMap(),
)
