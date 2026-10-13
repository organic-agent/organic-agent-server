package com.soma.wes.auth.domain

import com.fasterxml.jackson.annotation.JsonValue
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException

enum class OAuthProvider {
    GOOGLE,
    NAVER,
    KAKAO,
    ;

    @get:JsonValue
    val key: String
        get() = name.lowercase()

    companion object {

        fun from(value: String): OAuthProvider =
            entries.find { it.key == value.lowercase() }
                ?: throw OAuthException(AuthErrorCode.PROVIDER_NOT_SUPPORTED)
    }
}
