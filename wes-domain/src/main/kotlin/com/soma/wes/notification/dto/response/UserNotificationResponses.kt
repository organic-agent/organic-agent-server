package com.soma.wes.notification.dto.response

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
    @field:Schema(
        description = "알림이 속한 대상의 id(STUDIO면 스튜디오 작업공간 id, GALLERY면 갤러리 id). GLOBAL 알림이면 null이다.",
        example = "42",
    )
    val scopeId: Long?,
    val title: String,
    val message: String,
    @field:Schema(
        description = "읽음 처리된 시각. null이면 아직 읽지 않은 알림이고, 그 개수가 읽지 않음 수다.",
        example = "2026-11-04T10:15:30+09:00",
    )
    val readAt: ZonedDateTime?,
    @field:Schema(
        description = "알림이 생긴 시각. 저장할 때 서버가 채우며, 목록은 이 값의 최신순으로 최근 30일만 담는다.",
        example = "2026-11-04T09:00:00+09:00",
    )
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
