package com.soma.wes.admin.dto.request

import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.support.AdminPasswordHasher
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AdminLoginRequest(
    @field:NotBlank
    @field:Size(max = AdminAccount.USERNAME_MAX_LENGTH)
    @field:Schema(description = "개별 최고 관리자 아이디")
    val username: String,

    @field:NotBlank
    @field:Size(max = AdminPasswordHasher.MAX_LENGTH)
    @field:Schema(description = "최고 관리자 비밀번호")
    val password: String,
)
