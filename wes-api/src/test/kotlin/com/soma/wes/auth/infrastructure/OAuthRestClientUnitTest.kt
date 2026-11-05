package com.soma.wes.auth.infrastructure

import com.soma.wes.auth.dto.OAuthRegistrationDto
import com.soma.wes.auth.exception.AuthErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

class OAuthRestClientUnitTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = OAuthRestClient(builder.build())

    private val registration = OAuthRegistrationDto(
        clientId = "client-id",
        clientSecret = "client-secret",
        redirectUri = "https://wes.test/oauth/callback",
        authorizationUri = "https://social.test/authorize",
        tokenUri = "https://social.test/token",
        userInfoUri = "https://social.test/me",
        scopes = setOf("profile"),
    )

    @Nested
    @DisplayName("인가 코드를 바꿀 때")
    inner class ExchangeCode {

        @Test
        fun `토큰 주소로 인가 코드 폼을 보내고 access token을 꺼낸다`() {
            // given
            val form = LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("client_id", "client-id")
                add("client_secret", "client-secret")
                add("redirect_uri", "https://wes.test/oauth/callback")
                add("code", "auth-code")
            }
            server.expect(requestTo("https://social.test/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(form))
                .andRespond(withSuccess("""{"access_token":"social-token"}""", MediaType.APPLICATION_JSON))

            // when
            val accessToken = client.exchangeCode(registration, "auth-code")

            // then
            assertThat(accessToken).isEqualTo("social-token")
            server.verify()
        }

        @Test
        fun `응답에 access token이 없으면 응답 오류로 막는다`() {
            // given
            server.expect(requestTo("https://social.test/token"))
                .andRespond(withSuccess("""{"token_type":"bearer"}""", MediaType.APPLICATION_JSON))

            // when & then
            assertThatThrownBy { client.exchangeCode(registration, "auth-code") }
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.OAUTH_RESPONSE_INVALID)
        }

        @Test
        fun `소셜 서버가 4xx로 거절하면 인가 코드 오류로 막는다`() {
            // given
            server.expect(requestTo("https://social.test/token"))
                .andRespond(withBadRequest())

            // when & then
            assertThatThrownBy { client.exchangeCode(registration, "auth-code") }
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.AUTH_CODE_INVALID)
        }
    }

    @Nested
    @DisplayName("사용자 정보를 받을 때")
    inner class FetchUserAttributes {

        @Test
        fun `사용자 정보 주소로 Bearer 토큰을 보내고 응답을 그대로 돌려준다`() {
            // given
            server.expect(requestTo("https://social.test/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer social-token"))
                .andRespond(withSuccess("""{"id":"42"}""", MediaType.APPLICATION_JSON))

            // when
            val attributes = client.fetchUserAttributes(registration, "social-token")

            // then
            assertThat(attributes).containsEntry("id", "42")
            server.verify()
        }
    }
}
