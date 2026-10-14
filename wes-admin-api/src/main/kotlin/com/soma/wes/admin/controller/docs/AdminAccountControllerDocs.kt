package com.soma.wes.admin.controller.docs

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.dto.request.AdminReasonRequest
import com.soma.wes.admin.dto.request.ChangeAdminStatusRequest
import com.soma.wes.admin.dto.request.CreateAdminAccountRequest
import com.soma.wes.admin.dto.response.AdminAccountListResponse
import com.soma.wes.admin.dto.response.AdminAccountResponse
import com.soma.wes.admin.dto.response.AdminTemporaryPasswordResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody

@Tag(name = "최고 관리자 계정")
interface AdminAccountControllerDocs {

    @Operation(summary = "최고 관리자 계정 목록")
    fun getAccounts(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
    ): ResponseEntity<AdminAccountListResponse>

    @Operation(summary = "최고 관리자 계정 생성", description = "임시 비밀번호는 응답에서 한 번만 제공합니다.")
    fun create(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @Valid @RequestBody request: CreateAdminAccountRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminTemporaryPasswordResponse>

    @Operation(summary = "최고 관리자 계정 활성·정지")
    fun changeStatus(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable adminId: Long,
        @Valid @RequestBody request: ChangeAdminStatusRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminAccountResponse>

    @Operation(summary = "최고 관리자 계정 잠금 해제")
    fun unlock(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable adminId: Long,
        @Valid @RequestBody request: AdminReasonRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminAccountResponse>

    @Operation(summary = "다른 최고 관리자 임시 비밀번호 발급", description = "기존 세션은 모두 폐기하고 새 임시 비밀번호는 응답에서 한 번만 제공합니다.")
    fun issueTemporaryPassword(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable adminId: Long,
        @Valid @RequestBody request: AdminReasonRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminTemporaryPasswordResponse>
}
