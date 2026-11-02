package com.soma.wes.admin.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AdminReasonRequest(
    @field:NotBlank
    @field:Size(max = 500)
    @field:Schema(description = "잠금 해제 또는 임시 비밀번호 발급 사유")
    val reason: String,
)
