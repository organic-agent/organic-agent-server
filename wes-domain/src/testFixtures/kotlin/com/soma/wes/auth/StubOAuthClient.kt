package com.soma.wes.auth

import com.soma.wes.auth.dto.OAuthRegistrationDto
import com.soma.wes.auth.service.port.OAuthClient

/**
 * 소셜 로그인 서버 대역. 토큰 교환은 늘 성공하고, 사용자 정보는 [givenKakaoUser]로 정한 카카오 응답을 돌려준다.
 */
class StubOAuthClient : OAuthClient {

    private var userInfo: Map<String, Any> = emptyMap()

    fun givenKakaoUser(providerId: String, nickname: String, email: String) {
        userInfo = mapOf(
            "id" to providerId,
            "kakao_account" to mapOf("email" to email, "profile" to mapOf("nickname" to nickname)),
        )
    }

    override fun exchangeCode(registration: OAuthRegistrationDto, code: String): String = "stub-access-token"

    override fun fetchUserAttributes(registration: OAuthRegistrationDto, accessToken: String): Map<String, Any> = userInfo
}
