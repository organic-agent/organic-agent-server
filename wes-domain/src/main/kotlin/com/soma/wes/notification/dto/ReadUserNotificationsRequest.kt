package com.soma.wes.notification.dto

import com.soma.wes.notification.domain.UserNotificationScope
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

data class ReadUserNotificationsRequest(
    @field:Size(max = 200)
    @field:Schema(description = "읽을 알림 id. all과 함께 지정할 수 없다.")
    val notificationIds: List<Long> = emptyList(),
    @field:Schema(description = "true이면 선택한 범위의 최근 30일 알림을 모두 읽는다.")
    val all: Boolean = false,
    val scope: UserNotificationScope? = null,
    val scopeId: Long? = null,
)
