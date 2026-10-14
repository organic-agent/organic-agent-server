package com.soma.wes.admin.controller

import com.soma.wes.admin.controller.docs.AdminAccountControllerDocs
import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.dto.request.AdminReasonRequest
import com.soma.wes.admin.dto.request.ChangeAdminStatusRequest
import com.soma.wes.admin.dto.request.CreateAdminAccountRequest
import com.soma.wes.admin.dto.response.AdminAccountListResponse
import com.soma.wes.admin.dto.response.AdminAccountResponse
import com.soma.wes.admin.dto.response.AdminTemporaryPasswordResponse
import com.soma.wes.admin.service.AdminAccountService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/admin/v1/admins")
class AdminAccountController(
    private val adminAccountService: AdminAccountService,
) : AdminAccountControllerDocs {

    @GetMapping
    override fun getAccounts(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
    ): ResponseEntity<AdminAccountListResponse> {
        val result = adminAccountService.getAccounts()

        return ResponseEntity.ok(result)
    }

    @PostMapping
    override fun create(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @Valid @RequestBody request: CreateAdminAccountRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminTemporaryPasswordResponse> {
        val result = adminAccountService.create(loginUser.id, request, servletRequest.remoteAddr)

        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    @PatchMapping("/{adminId}/status")
    override fun changeStatus(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable adminId: Long,
        @Valid @RequestBody request: ChangeAdminStatusRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminAccountResponse> {
        val result = adminAccountService.changeStatus(loginUser.id, adminId, request, servletRequest.remoteAddr)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/{adminId}/unlock")
    override fun unlock(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable adminId: Long,
        @Valid @RequestBody request: AdminReasonRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminAccountResponse> {
        val result = adminAccountService.unlock(loginUser.id, adminId, request, servletRequest.remoteAddr)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/{adminId}/temporary-password")
    override fun issueTemporaryPassword(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable adminId: Long,
        @Valid @RequestBody request: AdminReasonRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminTemporaryPasswordResponse> {
        val result = adminAccountService.issueTemporaryPassword(
            actorAdminId = loginUser.id,
            targetAdminId = adminId,
            request = request,
            sourceAddress = servletRequest.remoteAddr,
        )

        return ResponseEntity.ok(result)
    }
}
