package com.soma.wes.auth.dto

import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException

/** 계정의 탈퇴·정지 여부. 토큰은 유효해도 계정이 이 상태면 요청을 받지 않는다. */
data class AccessStatusDto(
    val deleted: Boolean,
    val suspended: Boolean,
)

/** 계정이 없거나(null) 탈퇴했으면 다시 로그인(401), 정지됐으면 정지 응답. */
fun AccessStatusDto?.requireActive() {
    when {
        this == null || deleted -> throw TokenException(AuthErrorCode.TOKEN_OWNER_NOT_FOUND)
        suspended -> throw TokenException(AuthErrorCode.USER_SUSPENDED)
    }
}
