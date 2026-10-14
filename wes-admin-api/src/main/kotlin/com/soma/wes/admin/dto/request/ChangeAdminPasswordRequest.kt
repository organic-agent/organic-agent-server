package com.soma.wes.admin.dto.request

import com.soma.wes.admin.support.AdminPasswordHasher
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ChangeAdminPasswordRequest(
    @field:NotBlank
    @field:Size(max = AdminPasswordHasher.MAX_LENGTH)
    @field:Schema(description = "현재 비밀번호")
    val currentPassword: String,

    @field:NotBlank
    @field:Size(min = AdminPasswordHasher.MIN_LENGTH, max = AdminPasswordHasher.MAX_LENGTH)
    @field:Schema(description = "새 비밀번호. 12자 이상 128자 이하")
    val newPassword: String,
)
