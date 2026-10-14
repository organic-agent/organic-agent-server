package com.soma.wes.admin.controller.docs

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.dto.request.AdminLoginRequest
import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.dto.response.AdminSessionResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.RequestBody

@Tag(name = "최고 관리자 인증")
interface AdminAuthControllerDocs {

    @Operation(summary = "최고 관리자 로그인", description = "개별 관리자 계정으로 로그인하고 8시간·30분 비활동 제한 세션을 발급합니다.")
    fun login(
        @Valid @RequestBody request: AdminLoginRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminSessionResponse>

    @Operation(summary = "현재 최고 관리자 세션")
    fun session(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminSessionResponse>

    @Operation(summary = "최고 관리자 로그아웃")
    fun logout(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit>

    @Operation(summary = "최고 관리자 비밀번호 변경", description = "임시 비밀번호를 포함한 현재 비밀번호를 검증한 뒤 모든 기존 세션을 폐기합니다.")
    fun changePassword(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @Valid @RequestBody request: ChangeAdminPasswordRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminSessionResponse>
}
