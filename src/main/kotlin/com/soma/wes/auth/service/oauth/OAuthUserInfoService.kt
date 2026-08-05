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


@Service
class OAuthUserInfoService(
    private val registrations: OAuthRegistrations,
    private val oAuthClient: OAuthClient,
    private val extractorFactory: OAuthUserInfoExtractorFactory,
) {

    companion object {
        private const val GRANT_TYPE = "authorization_code"

        // %XX 형태의 퍼센트 이스케이프가 실제로 있을 때만 디코딩 대상으로 본다.
        private val PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")
    }

    fun getUserInfo(provider: OAuthProvider, authCode: String, requestOrigin: String? = null): OAuthUserInfo {
        if (authCode.isBlank()) {
            throw OAuthException(AuthErrorCode.AUTH_CODE_INVALID)
        }
        val registration = registrations.of(provider, requestOrigin)

        val accessToken = exchangeAuthCodeForAccessToken(registration, normalizeAuthCode(authCode))
        val attributes = oAuthClient.getUserInfoWithAccessToken(registration.userInfoUri, accessToken)

        return extractorFactory.resolve(provider).extract(attributes)
    }

    private fun normalizeAuthCode(authCode: String): String =
        if (PERCENT_ESCAPE.containsMatchIn(authCode)) {
            URLDecoder.decode(authCode, StandardCharsets.UTF_8)
        } else {
            authCode
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
