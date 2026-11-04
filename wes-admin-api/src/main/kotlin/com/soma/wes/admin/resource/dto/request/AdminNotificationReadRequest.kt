package com.soma.wes.admin.resource.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.PositiveOrZero

data class AdminNotificationReadRequest(
    @field:PositiveOrZero
    @field:Schema(
        description = "목록에서 받은 알림의 version. 다르면 409(ADMIN_409_5)이고, 이미 읽은 알림에 다시 보내도 성공한다.",
        example = "0",
    )
    val expectedVersion: Long,
)
