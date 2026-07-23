package com.soma.wes.auth.service.oauth

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

    /**
     * @param requestOrigin 요청의 `Origin` 헤더. redirect-uri를 어느 프론트로 잡을지 정하는 데만 쓴다.
     *   자세한 규칙은 [OAuthRedirectUriResolver]에 있다. 넘기지 않으면 설정값이 그대로 쓰인다.
     */
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

    /**
     * Spring은 공개 클라이언트나 다른 grant type도 지원하느라 이 값들을 nullable로 선언한다.
     * 우리 플로우(authorization_code + 서버가 사용자 정보 조회)에서는 모두 필수다.
     *
     * null만 걸러서는 안 된다. `client-secret`을 적지 않으면 Spring은 null이 아니라 **빈 문자열**을
     * 담아둔다. 그대로 통과시키면 `client_secret=`를 빈 값으로 보내고, provider가 거부한 것을
     * `AUTH_400_2`(인가 코드가 유효하지 않음)로 보고하게 된다. 우리 설정 문제인데 클라이언트를 탓하는 꼴이다.
     *
     * 클라이언트가 고칠 수 있는 문제가 아니므로 400이 아니라 500으로 응답한다.
     */
    private fun String?.orMisconfigured(): String =
        if (isNullOrBlank()) throw OAuthException(AuthErrorCode.OAUTH_MISCONFIGURED) else this
}

/**
 * 빠진 값이 없음이 확인된 provider 설정.
 *
 * 서비스는 이 타입만 보므로 Spring의 nullable 값을 만질 일도, 매번 null을 확인할 일도 없다.
 */
data class OAuthRegistration(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val authorizationUri: String,
    val tokenUri: String,
    val userInfoUri: String,
    val scopes: Set<String>,
)
