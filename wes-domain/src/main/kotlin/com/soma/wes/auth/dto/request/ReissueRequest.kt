package com.soma.wes.auth.dto.request

import io.swagger.v3.oas.annotations.media.Schema

data class ReissueRequest(
    @field:Schema(
        description = "가장 최근에 발급받은 refresh token. 재발급하면 새 refresh token으로 교체되어 보낸 토큰은 즉시 무효가 되고, 로그아웃·다른 기기 재로그인으로 갈린 토큰은 401이다.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl",
    )
    val refreshToken: String,
)
