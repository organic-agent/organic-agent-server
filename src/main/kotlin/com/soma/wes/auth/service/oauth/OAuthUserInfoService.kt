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

        // %XX 형태의 퍼센트 이스케이프가 실제로 있을 때만 디코딩 대상으로 본다.
        private val PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")
    }

    /**
     * @param requestOrigin 요청의 `Origin` 헤더. 토큰 교환에 보낼 `redirect_uri`를 정한다.
     *   인가 URL을 만들 때([OAuthLoginUrlService])와 값이 달라지면 provider가 거절하므로,
     *   두 요청이 같은 출처에서 와야 한다.
     */
    fun getUserInfo(provider: OAuthProvider, authCode: String, requestOrigin: String? = null): OAuthUserInfo {
        if (authCode.isBlank()) {
            throw OAuthException(AuthErrorCode.AUTH_CODE_INVALID)
        }
        val registration = registrations.of(provider, requestOrigin)

        val accessToken = exchangeAuthCodeForAccessToken(registration, normalizeAuthCode(authCode))
        val attributes = oAuthClient.getUserInfoWithAccessToken(registration.userInfoUri, accessToken)

        return extractorFactory.resolve(provider).extract(attributes)
    }

    /**
     * 프론트가 인가 코드를 URL 인코딩된 채로 넘기는 경우가 있어 되돌린다.
     * 단 이미 디코딩된 raw 코드를 무조건 디코딩하면 `+`가 공백으로 깨지므로,
     * 퍼센트 이스케이프(%XX)가 실제로 있을 때만 디코딩한다.
     */
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
