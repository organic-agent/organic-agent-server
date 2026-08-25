package com.soma.wes.admin.dto.response

data class AdminTemporaryPasswordResponse(
    val account: AdminAccountResponse,
    val temporaryPassword: String,
)
