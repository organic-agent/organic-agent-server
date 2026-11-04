package com.soma.wes.notification.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "사용자 알림 수신 설정")
data class UpdateUserNotificationSettingsRequest(
    val emailEnabled: Boolean,
    val browserEnabled: Boolean,
)
