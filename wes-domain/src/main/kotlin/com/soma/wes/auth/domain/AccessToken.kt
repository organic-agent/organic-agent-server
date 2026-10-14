package com.soma.wes.auth.domain

import com.soma.wes.auth.token.TokenType

@JvmInline
value class AccessToken(override val value: String) : Token {

    override val type: TokenType
        get() = TokenType.ACCESS
}
