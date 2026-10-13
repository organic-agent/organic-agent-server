package com.soma.wes.notification.dto

import com.soma.wes.notification.domain.UserNotification
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationSetting
import com.soma.wes.notification.domain.UserNotificationType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class UserNotificationResponse(
    val id: Long,
    val type: UserNotificationType,
    val scope: UserNotificationScope,
    val scopeId: Long?,
    val title: String,
    val message: String,
    val readAt: ZonedDateTime?,
    val createdAt: ZonedDateTime?,
) {
    companion object {
        fun from(notification: UserNotification) = UserNotificationResponse(
            id = notification.requiredId,
            type = notification.type,
            scope = notification.scope,
            scopeId = notification.scopeId,
            title = notification.title,
            message = notification.message,
            readAt = notification.readAt,
            createdAt = notification.createdAt,
        )
    }
}

@Schema(description = "사용자 알림 수신 설정")
data class UpdateUserNotificationSettingsRequest(
    val emailEnabled: Boolean,
    val browserEnabled: Boolean,
)

data class UserNotificationSettingsResponse(
    val emailEnabled: Boolean,
    val browserEnabled: Boolean,
) {
    companion object {
        fun defaults() = UserNotificationSettingsResponse(emailEnabled = true, browserEnabled = true)

        fun from(setting: UserNotificationSetting) = UserNotificationSettingsResponse(
            emailEnabled = setting.emailEnabled,
            browserEnabled = setting.browserEnabled,
        )
    }
}
