package com.soma.wes.admin.controller

import com.soma.wes.admin.config.AdminAuthProperties
import com.soma.wes.admin.controller.docs.AdminAuthControllerDocs
import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.dto.request.AdminLoginRequest
import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.dto.response.AdminSessionResponse
import com.soma.wes.admin.exception.AdminAuthenticationException
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.service.AdminAuthService
import com.soma.wes.admin.service.AdminSessionService
import com.soma.wes.admin.support.AdminSessionCookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/admin/v1/auth")
class AdminAuthController(
    private val adminAuthService: AdminAuthService,
    private val adminSessionService: AdminSessionService,
    private val properties: AdminAuthProperties,
) : AdminAuthControllerDocs {

    @PostMapping("/login")
    override fun login(
        @Valid @RequestBody request: AdminLoginRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminSessionResponse> {
        val result = adminAuthService.login(request, servletRequest.remoteAddr)

        return ResponseEntity.ok()
            .header(
                HttpHeaders.SET_COOKIE,
                AdminSessionCookie.create(result.rawSessionToken, properties.session.absoluteTtl).toString(),
            )
            .body(result.response)
    }

    @GetMapping("/session")
    override fun session(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminSessionResponse> {
        val result = adminSessionService.getCurrent(loginUser.id, requireSessionToken(servletRequest))

        return ResponseEntity.ok(result)
    }

    @PostMapping("/logout")
    override fun logout(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        adminAuthService.logout(loginUser, requireSessionToken(servletRequest), servletRequest.remoteAddr)

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, AdminSessionCookie.delete().toString())
            .build()
    }

    @PostMapping("/change-password")
    override fun changePassword(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @Valid @RequestBody request: ChangeAdminPasswordRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminSessionResponse> {
        val result = adminAuthService.changePassword(loginUser.id, request, servletRequest.remoteAddr)

        return ResponseEntity.ok()
            .header(
                HttpHeaders.SET_COOKIE,
                AdminSessionCookie.create(result.rawSessionToken, properties.session.absoluteTtl).toString(),
            )
            .body(result.response)
    }

    private fun requireSessionToken(request: HttpServletRequest): String =
        request.cookies
            ?.firstOrNull { it.name == AdminSessionCookie.NAME }
            ?.value
            ?: throw AdminAuthenticationException(AdminErrorCode.SESSION_INVALID)
}
