package com.soma.wes.auth.domain

import com.soma.wes.auth.domain.TokenType
import java.security.MessageDigest
import java.util.HexFormat

@JvmInline
value class RefreshToken(override val value: String) : Token {

    override val type: TokenType
        get() = TokenType.REFRESH

    /**
     * 저장소에 남기는 값. 토큰 문자열의 UTF-8 바이트를 SHA-256 → 소문자 16진수로 만든다.
     *
     * 토큰은 서명된 JWT라 추측할 수 없는 값이어서 솔트·느린 해시(bcrypt)가 필요 없다.
     * V54 마이그레이션이 기존 평문을 같은 식(`encode(sha256(convert_to(token, 'UTF8')), 'hex')`)으로 바꿨다 — 둘을 함께 바꾼다.
     */
    fun hashed(): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}
