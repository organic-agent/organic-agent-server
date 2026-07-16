package com.soma.wes.auth.dto.request

/**
 * `code`를 non-null로 선언해두면 JSON에 값이 없을 때 역직렬화 단계에서 걸러져 400이 응답된다.
 */
data class AuthCodeRequest(
    val code: String,
)