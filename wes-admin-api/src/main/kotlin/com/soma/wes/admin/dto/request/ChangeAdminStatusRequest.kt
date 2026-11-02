package com.soma.wes.admin.dto.request

import com.soma.wes.admin.domain.AdminAccountStatus
import io.swagger.v3.oas.annotations.media.Schema

data class ChangeAdminStatusRequest(
    @field:Schema(description = "ACTIVE 또는 SUSPENDED")
    val status: AdminAccountStatus,
)
