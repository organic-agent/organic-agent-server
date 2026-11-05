package com.soma.wes.auth.dto

/**
 * 서명 검증을 통과한 토큰의 내용.
 *
 * @property subject 표준 `sub` 클레임. 없을 수도 있어 호출자가 검사한다.
 * @property claims `sub`를 포함한 모든 클레임.
 */
data class TokenClaimsDto(
    val subject: String?,
    val claims: Map<String, Any?>,
) {
    operator fun get(key: String): Any? = claims[key]
}
