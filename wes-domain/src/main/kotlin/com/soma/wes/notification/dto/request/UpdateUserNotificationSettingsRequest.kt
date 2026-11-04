package com.soma.wes.notification.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "사용자 알림 수신 설정")
data class UpdateUserNotificationSettingsRequest(
    @field:Schema(
        description = "이메일 알림 수신 여부. 두 토글을 한 번에 덮어쓰므로 바꾸지 않는 값도 현재 값으로 보낸다. 설정한 적 없으면 true다.",
        example = "true",
    )
    val emailEnabled: Boolean,
    @field:Schema(
        description = "브라우저 알림 수신 여부. 두 토글을 한 번에 덮어쓰므로 바꾸지 않는 값도 현재 값으로 보낸다. 설정한 적 없으면 true다.",
        example = "false",
    )
    val browserEnabled: Boolean,
)
