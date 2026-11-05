package com.soma.wes.auth.service

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.OAuthUserInfoDto
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException
import com.soma.wes.auth.service.port.OAuthClient
import com.soma.wes.auth.support.OAuthUserInfoExtractorFactory
import com.soma.wes.auth.support.OAuthRegistrations
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.springframework.stereotype.Service

@Service
class OAuthUserInfoService(
    private val registrations: OAuthRegistrations,
    private val oAuthClient: OAuthClient,
    private val extractorFactory: OAuthUserInfoExtractorFactory,
) {

    companion object {
        // %XX 형태의 퍼센트 이스케이프가 실제로 있을 때만 디코딩 대상으로 본다.
        private val PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")
    }

    fun getUserInfo(provider: OAuthProvider, authCode: String, requestOrigin: String? = null): OAuthUserInfoDto {
        if (authCode.isBlank()) {
            throw OAuthException(AuthErrorCode.AUTH_CODE_INVALID)
        }
        val registration = registrations.of(provider, requestOrigin)

        val accessToken = oAuthClient.exchangeCode(registration, normalizeAuthCode(authCode))
        val attributes = oAuthClient.fetchUserAttributes(registration, accessToken)

        return extractorFactory.resolve(provider).extract(attributes)
    }

    private fun normalizeAuthCode(authCode: String): String =
        if (PERCENT_ESCAPE.containsMatchIn(authCode)) {
            URLDecoder.decode(authCode, StandardCharsets.UTF_8)
        } else {
            authCode
        }
}
