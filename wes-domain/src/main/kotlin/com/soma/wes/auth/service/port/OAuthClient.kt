package com.soma.wes.auth.service.port

import org.springframework.util.MultiValueMap

/**
 * 소셜 로그인 서버와의 HTTP 통신. 구현을 갈아끼울 수 있도록 인터페이스로 분리한다.
 */
interface OAuthClient {

    fun getAccessTokenResponse(tokenUri: String, tokenRequest: MultiValueMap<String, String>): Map<String, Any>

    fun getUserInfoWithAccessToken(userInfoUri: String, accessToken: String): Map<String, Any>
}
