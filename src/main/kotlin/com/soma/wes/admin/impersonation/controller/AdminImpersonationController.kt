package com.soma.wes.admin.impersonation.controller

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.impersonation.dto.AdminImpersonationResponse
import com.soma.wes.admin.impersonation.dto.StartAdminImpersonationRequest
import com.soma.wes.admin.impersonation.service.AdminImpersonationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/internal/admin/v1/impersonations")
class AdminImpersonationController(
    private val service: AdminImpersonationService,
) {

    @PostMapping
    fun start(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @Valid @RequestBody request: StartAdminImpersonationRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminImpersonationResponse> = ResponseEntity
        .status(HttpStatus.CREATED)
        .body(service.start(loginUser, request, servletRequest.remoteAddr))

    @GetMapping("/{id}")
    fun view(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable id: UUID,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminImpersonationResponse> = ResponseEntity.ok(
        service.view(loginUser, id, servletRequest.remoteAddr),
    )

    @DeleteMapping("/{id}")
    fun end(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable id: UUID,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Void> {
        service.end(loginUser, id, servletRequest.remoteAddr)
        return ResponseEntity.noContent().build()
    }
}
