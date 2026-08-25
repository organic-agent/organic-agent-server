package com.soma.wes.admin.dto.request

import com.soma.wes.admin.domain.AdminAuthEvent
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AdminReasonRequest(
    @field:NotBlank
    @field:Size(max = AdminAuthEvent.REASON_MAX_LENGTH)
    @field:Schema(description = "잠금 해제 또는 임시 비밀번호 발급 사유")
    val reason: String,
)
