package com.soma.wes.auth.service.oauth

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.OAuthUserInfo
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException
import com.soma.wes.auth.strategy.OAuthUserInfoExtractorFactory
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 인가 코드를 access token으로 교환하고, 그 토큰으로 provider에서 사용자 정보를 가져온다.
 * client-secret이 필요한 단계라 반드시 서버에서 수행해야 한다.
 */
@Service
class OAuthUserInfoService(
    private val registrations: OAuthRegistrations,
    private val oAuthClient: OAuthClient,
    private val extractorFactory: OAuthUserInfoExtractorFactory,
) {

    companion object {
        private const val GRANT_TYPE = "authorization_code"
    }

    fun getUserInfo(provider: OAuthProvider, authCode: String): OAuthUserInfo {
        if (authCode.isBlank()) {
            throw OAuthException(AuthErrorCode.AUTH_CODE_INVALID)
        }
        val registration = registrations.of(provider)

        // 웹에서 인가 코드가 URL 인코딩된 채로 전달되는 경우가 있다.
        val decodedCode = URLDecoder.decode(authCode, StandardCharsets.UTF_8)

        val accessToken = exchangeAuthCodeForAccessToken(registration, decodedCode)
        val attributes = oAuthClient.getUserInfoWithAccessToken(registration.userInfoUri, accessToken)

        return extractorFactory.resolve(provider).extract(attributes)
    }

    private fun exchangeAuthCodeForAccessToken(registration: OAuthRegistration, code: String): String {
        val tokenRequest = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", GRANT_TYPE)
            add("client_id", registration.clientId)
            add("client_secret", registration.clientSecret)
            add("redirect_uri", registration.redirectUri)
            add("code", code)
        }

        val tokenResponse = oAuthClient.getAccessTokenResponse(registration.tokenUri, tokenRequest)

        return tokenResponse["access_token"] as? String
            ?: throw OAuthException(AuthErrorCode.OAUTH_RESPONSE_INVALID)
    }
}
