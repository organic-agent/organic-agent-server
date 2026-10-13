package com.soma.wes.auth.support

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.stereotype.Component

/**
 * provider의 OAuth 설정(`spring.security.oauth2.client.registration.*`)을 읽는다.
 *
 * 설정을 읽는 일은 로그인 URL을 만드는 일도, 사용자 정보를 가져오는 일도 아니다.
 * 두 서비스가 함께 쓰는 관심사이므로 어느 한쪽에 얹지 않고 여기 하나가 맡는다.
 */
@Component
class OAuthRegistrations(
    private val clientRegistrationRepository: ClientRegistrationRepository,
    private val redirectUriResolver: OAuthRedirectUriResolver,
) {


    fun of(provider: OAuthProvider, requestOrigin: String? = null): OAuthRegistration {
        val registration = clientRegistrationRepository.findByRegistrationId(provider.key)
            ?: throw OAuthException(AuthErrorCode.PROVIDER_NOT_SUPPORTED)
        val details = registration.providerDetails

        return OAuthRegistration(
            clientId = registration.clientId,
            clientSecret = registration.clientSecret.orMisconfigured(),
            redirectUri = redirectUriResolver.resolve(registration.redirectUri.orMisconfigured(), requestOrigin),
            authorizationUri = details.authorizationUri.orMisconfigured(),
            tokenUri = details.tokenUri,
            userInfoUri = details.userInfoEndpoint.uri.orMisconfigured(),
            scopes = registration.scopes,
        )
    }

    private fun String?.orMisconfigured(): String =
        if (isNullOrBlank()) throw OAuthException(AuthErrorCode.OAUTH_MISCONFIGURED) else this
}


data class OAuthRegistration(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val authorizationUri: String,
    val tokenUri: String,
    val userInfoUri: String,
    val scopes: Set<String>,
)
