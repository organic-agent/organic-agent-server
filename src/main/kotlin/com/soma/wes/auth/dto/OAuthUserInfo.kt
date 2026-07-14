package com.soma.wes.auth.dto

import com.soma.wes.auth.domain.OAuthProvider

/**
 * provider마다 제각각인 사용자 정보 응답을 하나의 형태로 정규화한 것.
 * 클라이언트에 나가는 값이 아니라 서비스 계층 사이에서만 오간다.
 */
data class OAuthUserInfo(
    val provider: OAuthProvider,
    val providerId: String,
    val nickname: String,
    val email: String?,
)
