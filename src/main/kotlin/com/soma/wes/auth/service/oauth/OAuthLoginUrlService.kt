package com.soma.wes.auth.service.oauth

import com.soma.wes.auth.domain.OAuthProvider
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder

/**
 * provider의 인가 페이지로 보낼 로그인 URL을 만든다.
 * client-id·redirect-uri·scope를 서버가 쥐고 있으므로 클라이언트는 이 URL을 받아 이동하기만 하면 되고,
 * provider 설정이 바뀌어도 클라이언트를 고칠 필요가 없다.
 */
@Service
class OAuthLoginUrlService(
    private val registrations: OAuthRegistrations,
) {

    /**
     * @param requestOrigin 요청의 `Origin` 헤더. 어느 프론트로 돌려보낼지를 정한다([OAuthRedirectUriResolver]).
     *   여기서 만든 URL의 `redirect_uri`는 나중에 [OAuthUserInfoService]가 코드를 교환할 때와
     *   같은 값이어야 하므로, 그쪽에도 같은 헤더가 전달돼야 한다.
     */
    fun generateLoginUrl(provider: OAuthProvider, requestOrigin: String? = null): String {
        val registration = registrations.of(provider, requestOrigin)

        return UriComponentsBuilder.fromUriString(registration.authorizationUri)
            .queryParam("client_id", registration.clientId)
            .queryParam("response_type", "code")
            .queryParam("redirect_uri", registration.redirectUri)
            // OAuth2 스펙상 scope 구분자는 공백이다. 공백과 redirect_uri의 `:`, `/`는 반드시 인코딩되어야 하므로
            // build()로 끝내지 않고 encode()를 거친다.
            .queryParam("scope", registration.scopes.joinToString(" "))
            .build()
            .encode()
            .toUriString()
    }
}
