package com.soma.wes.notification.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.dto.UpdateUserNotificationSettingsRequest
import com.soma.wes.notification.dto.UserNotificationResponse
import com.soma.wes.notification.dto.UserNotificationSettingsResponse
import com.soma.wes.notification.dto.ReadUserNotificationsRequest
import com.soma.wes.notification.dto.ReadUserNotificationsResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Notification]", description = "사용자 알림 API")
interface UserNotificationControllerDocs {
    @Operation(
        summary = "내 알림 목록",
        description = "scope와 scopeId로 스튜디오 또는 갤러리 범위를 좁힐 수 있다. 지정하지 않으면 전체 범위의 최근 30일 알림을 최신순으로 반환한다. readAt이 null인 항목 수가 읽지 않음 수다.",
    )
    fun list(
        loginUser: LoginUser,
        scope: UserNotificationScope?,
        scopeId: Long?,
    ): ResponseEntity<List<UserNotificationResponse>>

    @Operation(summary = "알림 읽음 처리", description = "notificationIds 또는 all=true로 본인의 최근 30일 알림만 읽는다. 다른 사용자의 id가 섞이면 전체를 거절한다.")
    fun read(loginUser: LoginUser, request: ReadUserNotificationsRequest): ResponseEntity<ReadUserNotificationsResponse>

    @Operation(summary = "내 알림 수신 설정 조회")
    fun getSettings(loginUser: LoginUser): ResponseEntity<UserNotificationSettingsResponse>

    @Operation(summary = "내 알림 수신 설정 저장", description = "이메일과 브라우저 알림 토글을 한 번에 저장한다.")
    fun updateSettings(
        loginUser: LoginUser,
        request: UpdateUserNotificationSettingsRequest,
    ): ResponseEntity<UserNotificationSettingsResponse>
}
