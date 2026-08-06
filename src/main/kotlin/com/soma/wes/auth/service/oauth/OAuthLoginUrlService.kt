package com.soma.wes.auth.service.oauth

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.response.LoginUrlResponse
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder


@Service
class OAuthLoginUrlService(
    private val registrations: OAuthRegistrations,
) {


    fun generateLoginUrl(provider: String, requestOrigin: String? = null): LoginUrlResponse {
        val registration = registrations.of(OAuthProvider.from(provider), requestOrigin)

        val loginUrl = UriComponentsBuilder.fromUriString(registration.authorizationUri)
            .queryParam("client_id", registration.clientId)
            .queryParam("response_type", "code")
            .queryParam("redirect_uri", registration.redirectUri)
            // OAuth2 스펙상 scope 구분자는 공백이다. 공백과 redirect_uri의 `:`, `/`는 반드시 인코딩되어야 하므로
            // build()로 끝내지 않고 encode()를 거친다.
            .queryParam("scope", registration.scopes.joinToString(" "))
            .build()
            .encode()
            .toUriString()

        return LoginUrlResponse.from(loginUrl)
    }
}
