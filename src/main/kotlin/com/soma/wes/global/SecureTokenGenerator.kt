package com.soma.wes.global

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/**
 * 링크에 실을 토큰을 만든다. 초대 링크·협업 링크·하객 세션이 함께 쓴다.
 */
@Component
class SecureTokenGenerator {

    private val random = SecureRandom()

    companion object {
        private const val TOKEN_BYTES = 32

        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return ENCODER.encodeToString(bytes)
    }
}
