package com.soma.wes.admin.resource.dto.request

import jakarta.validation.constraints.PositiveOrZero

data class CreateAdminResourceRequest(
    val fields: Map<String, Any?>,
)

data class UpdateAdminResourceRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    val fields: Map<String, Any?>,
)

data class ChangeAdminResourceStateRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
)
