package com.soma.wes.notification.dto.request

import com.soma.wes.notification.domain.UserNotificationScope
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

data class ReadUserNotificationsRequest(
    @field:Size(max = 200)
    @field:Schema(description = "읽을 알림 id. all과 함께 지정할 수 없다.")
    val notificationIds: List<Long> = emptyList(),
    @field:Schema(description = "true이면 선택한 범위의 최근 30일 알림을 모두 읽는다.")
    val all: Boolean = false,
    @field:Schema(
        description = "읽을 알림의 범위. STUDIO는 스튜디오 직접 알림과 소속 갤러리 알림을 함께 다룬다. 생략하면 전체 범위다.",
        example = "GALLERY",
    )
    val scope: UserNotificationScope? = null,
    @field:Schema(
        description = "scope 대상의 id(STUDIO면 스튜디오 작업공간 id, GALLERY면 갤러리 id). 생략하면 그 범위 전체이고, scope 없이 또는 GLOBAL과 함께 보내면 400이다.",
        example = "42",
    )
    val scopeId: Long? = null,
)
