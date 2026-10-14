package com.soma.wes.auth.domain

import com.soma.wes.auth.token.TokenType

@JvmInline
value class RefreshToken(override val value: String) : Token {

    override val type: TokenType
        get() = TokenType.REFRESH
}
