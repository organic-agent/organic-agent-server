package com.soma.wes.notification.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.notification.controller.docs.UserNotificationControllerDocs
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.dto.UpdateUserNotificationSettingsRequest
import com.soma.wes.notification.dto.UserNotificationResponse
import com.soma.wes.notification.dto.UserNotificationSettingsResponse
import com.soma.wes.notification.service.UserNotificationService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/notifications")
class UserNotificationController(
    private val notificationService: UserNotificationService,
) : UserNotificationControllerDocs {
    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @RequestParam(required = false) scope: UserNotificationScope?,
        @RequestParam(required = false) scopeId: Long?,
    ): ResponseEntity<List<UserNotificationResponse>> =
        ResponseEntity.ok(notificationService.list(loginUser.id, scope, scopeId))

    @GetMapping("/settings")
    override fun getSettings(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<UserNotificationSettingsResponse> =
        ResponseEntity.ok(notificationService.getSettings(loginUser.id))

    @PutMapping("/settings")
    override fun updateSettings(
        @AuthenticationPrincipal loginUser: LoginUser,
        @Valid @RequestBody request: UpdateUserNotificationSettingsRequest,
    ): ResponseEntity<UserNotificationSettingsResponse> =
        ResponseEntity.ok(notificationService.updateSettings(loginUser.id, request))
}
