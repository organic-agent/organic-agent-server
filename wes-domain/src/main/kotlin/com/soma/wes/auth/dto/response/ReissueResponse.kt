package com.soma.wes.auth.dto.response

import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.RefreshToken

data class ReissueResponse(
    val accessToken: String,
    val refreshToken: String,
) {

    companion object {
        fun of(accessToken: AccessToken, refreshToken: RefreshToken) = ReissueResponse(
            accessToken = accessToken.value,
            refreshToken = refreshToken.value,
        )
    }
}