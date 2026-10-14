package com.soma.wes.admin.dto.request

import com.soma.wes.admin.domain.AdminAccountStatus
import com.soma.wes.admin.domain.AdminAuthEvent
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ChangeAdminStatusRequest(
    @field:Schema(description = "ACTIVE 또는 SUSPENDED")
    val status: AdminAccountStatus,

    @field:NotBlank
    @field:Size(max = AdminAuthEvent.REASON_MAX_LENGTH)
    @field:Schema(description = "계정 상태 변경 사유")
    val reason: String,
)
