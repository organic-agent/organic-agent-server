package com.soma.wes.admin.resource.controller

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.resource.dto.AdminNotificationInboxItemResponse
import com.soma.wes.admin.resource.dto.AdminNotificationInboxPageResponse
import com.soma.wes.admin.resource.dto.AdminNotificationReadRequest
import com.soma.wes.admin.resource.service.AdminNotificationInboxService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/admin/v1/notifications")
class AdminNotificationInboxController(
    private val service: AdminNotificationInboxService,
) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<AdminNotificationInboxPageResponse> =
        ResponseEntity.ok(service.list(loginUser.id, page, size))

    @PostMapping("/{notificationId}/read")
    fun markRead(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable notificationId: Long,
        @Valid @RequestBody request: AdminNotificationReadRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminNotificationInboxItemResponse> = ResponseEntity.ok(
        service.markRead(loginUser.id, notificationId, request.expectedVersion, servletRequest.remoteAddr),
    )
}
