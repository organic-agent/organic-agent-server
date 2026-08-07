package com.soma.wes.auth.dto.response

import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.RefreshToken
import io.swagger.v3.oas.annotations.media.Schema

data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,

    /**
     * 초대 링크를 눌러 로그인한 경우 방금 들어온 갤러리. 그 외에는 null이다.
     *
     * 이 값이 있으면 프론트는 곧바로 해당 갤러리로 보내면 된다. 수락이 로그인과 한 번에
     * 끝나므로 별도 수락 호출이 필요 없다.
     */
    @field:Schema(description = "초대로 로그인했다면 방금 들어온 갤러리 id. 일반 로그인은 null")
    val galleryId: Long? = null,
) {

    companion object {
        fun of(accessToken: AccessToken, refreshToken: RefreshToken, galleryId: Long? = null) = LoginResponse(
            accessToken = accessToken.value,
            refreshToken = refreshToken.value,
            galleryId = galleryId,
        )
    }
}
