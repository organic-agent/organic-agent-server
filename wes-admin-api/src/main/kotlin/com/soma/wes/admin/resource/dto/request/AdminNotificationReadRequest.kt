package com.soma.wes.admin.resource.dto.request

import jakarta.validation.constraints.PositiveOrZero

data class AdminNotificationReadRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
)
