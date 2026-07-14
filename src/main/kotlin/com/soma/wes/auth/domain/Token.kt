package com.soma.wes.auth.domain

import com.soma.wes.auth.token.TokenType

/**
 * 발급된 JWT. 토큰 문자열과 그 용도를 함께 나른다.
 *
 * 용도를 타입으로 구분해두면 refresh token을 access token 자리에 넘기는 실수가 컴파일 단계에서 걸린다.
 */
sealed interface Token {
    val value: String
    val type: TokenType
}
