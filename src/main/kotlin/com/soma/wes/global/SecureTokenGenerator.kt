package com.soma.wes.global

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/**
 * 링크에 실을 토큰을 만든다. 초대 링크·협업 링크·하객 세션이 함께 쓴다.
 *
 * 셋 다 "URL이나 헤더에 그대로 실리고, 그 값을 아는 것이 곧 권한"이라는 성질이 같다. 도메인마다
 * 따로 두면 한쪽만 바이트 수를 줄이거나 인코더를 바꿔도 아무도 알아채지 못한다. 두 도메인이
 * 쓰기 시작한 시점에 `global`로 올렸다.
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
