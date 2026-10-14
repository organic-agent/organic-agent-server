package com.soma.wes.auth.dto.response

data class LoginUrlResponse(
    val loginUrl: String,
) {

    companion object {
        fun from(loginUrl: String) = LoginUrlResponse(loginUrl)
    }
}