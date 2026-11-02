package com.soma.wes.admin.dto.request

import com.soma.wes.admin.domain.AdminAccountStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ChangeAdminStatusRequest(
    @field:Schema(description = "ACTIVE 또는 SUSPENDED")
    val status: AdminAccountStatus,

    @field:NotBlank
    @field:Size(max = 500)
    @field:Schema(description = "계정 상태 변경 사유")
    val reason: String,
)
