package com.soma.wes.auth.strategy

import com.soma.wes.auth.dto.OAuthUserInfo
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException
import com.soma.wes.auth.domain.OAuthProvider

/**
 * provider마다 사용자 정보 응답의 구조가 달라, 각자의 JSON을 공통 [OAuthUserInfo]로 옮긴다.
 */
interface OAuthUserInfoExtractor {

    val provider: OAuthProvider

    fun extract(attributes: Map<String, Any>): OAuthUserInfo
}

/**
 * provider가 필수 값을 빼먹고 응답한 경우로, 우리 쪽에서 복구할 방법이 없어 실패시킨다.
 */
internal fun Map<*, *>.requireString(key: String): String =
    this[key]?.toString() ?: throw OAuthException(AuthErrorCode.OAUTH_RESPONSE_INVALID)

internal fun Map<*, *>.requireMap(key: String): Map<*, *> =
    this[key] as? Map<*, *> ?: throw OAuthException(AuthErrorCode.OAUTH_RESPONSE_INVALID)
