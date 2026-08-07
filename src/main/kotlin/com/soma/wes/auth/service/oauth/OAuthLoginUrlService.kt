package com.soma.wes.auth.service.oauth

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.response.LoginUrlResponse
import com.soma.wes.auth.support.OAuthRegistrations
import com.soma.wes.auth.support.OAuthStateStore
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder


@Service
class OAuthLoginUrlService(
    private val registrations: OAuthRegistrations,
    private val oAuthStateStore: OAuthStateStore,
) {

    /**
     * @param inviteToken 초대 링크를 눌러 들어온 경우의 토큰. 로그인을 마치면 이어서 수락까지 처리된다.
     *   토큰 자체는 provider로 나가지 않는다 — 나가는 것은 이 값에 매인 난수뿐이다.
     */
    fun generateLoginUrl(
        provider: String,
        requestOrigin: String? = null,
        inviteToken: String? = null,
    ): LoginUrlResponse {
        val registration = registrations.of(OAuthProvider.from(provider), requestOrigin)

        // 초대가 없어도 발급한다. state는 초대를 실어 나르기 전에 CSRF 방어 장치이고,
        // 그건 모든 로그인에 필요하다.
        val state = oAuthStateStore.issue(inviteToken)

        val loginUrl = UriComponentsBuilder.fromUriString(registration.authorizationUri)
            .queryParam("client_id", registration.clientId)
            .queryParam("response_type", "code")
            .queryParam("redirect_uri", registration.redirectUri)
            // OAuth2 스펙상 scope 구분자는 공백이다. 공백과 redirect_uri의 `:`, `/`는 반드시 인코딩되어야 하므로
            // build()로 끝내지 않고 encode()를 거친다.
            .queryParam("scope", registration.scopes.joinToString(" "))
            .queryParam("state", state)
            .build()
            .encode()
            .toUriString()

        return LoginUrlResponse.from(loginUrl)
    }
}
