package com.soma.wes.auth.dto.response

import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.RefreshToken
import io.swagger.v3.oas.annotations.media.Schema

data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,

    @field:Schema(description = "일반 로그인과 수락 전 로그인은 null. 초대 수락은 POST /invites/{token}/accept로 별도 처리한다.")
    val galleryId: Long? = null,
    @field:Schema(description = "로그인 후 미리보기와 명시적 수락을 진행할 초대 토큰. 없으면 null.")
    val inviteToken: String? = null,
) {

    companion object {
        fun of(accessToken: AccessToken, refreshToken: RefreshToken, galleryId: Long? = null) = LoginResponse(
            accessToken = accessToken.value,
            refreshToken = refreshToken.value,
            galleryId = galleryId,
        )
    }
}
