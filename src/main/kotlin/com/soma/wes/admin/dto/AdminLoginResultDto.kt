package com.soma.wes.admin.dto

import com.soma.wes.admin.dto.response.AdminSessionResponse

data class AdminLoginResultDto(
    val rawSessionToken: String,
    val response: AdminSessionResponse,
)
