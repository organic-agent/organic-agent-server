package com.soma.wes.auth.support

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

/**
 * 설정 리더를 public API 경계에서 확인한다.
 *
 * 보는 것은 둘이다: 등록된 provider의 `spring.security.oauth2.client.registration.*` 설정이
 * 도메인 DTO([OAuthRegistration])로 온전히 실려 오는지, 그리고 등록되지 않은 provider를
 * 물었을 때 어떤 실패가 나는지.
 */
@IntegrationTest
class OAuthRegistrationsTest @Autowired constructor(
    private val oAuthRegistrations: OAuthRegistrations,
    private val redirectUriResolver: OAuthRedirectUriResolver,
) {

    @Nested
    @DisplayName("등록된 provider의 설정을 읽을 때")
    inner class ReadRegistered {

        @Test
        fun `kakao는 설정된 값이 그대로 실려 온다`() {
            // when
            val registration = oAuthRegistrations.of(OAuthProvider.KAKAO)

            // then
            assertSoftly { softly ->
                softly.assertThat(registration.clientId).isEqualTo("test-client-id")
                softly.assertThat(registration.clientSecret).isEqualTo("test-client-secret")
                softly.assertThat(registration.redirectUri).isEqualTo("http://localhost:3000/login/oauth2/code/kakao")
                softly.assertThat(registration.authorizationUri).isEqualTo("https://kauth.kakao.com/oauth/authorize")
                softly.assertThat(registration.tokenUri).isEqualTo("https://kauth.kakao.com/oauth/token")
                softly.assertThat(registration.userInfoUri).isEqualTo("https://kapi.kakao.com/v2/user/me")
                softly.assertThat(registration.scopes).containsExactlyInAnyOrder("profile_nickname", "account_email")
            }
        }

        @Test
        fun `google과 naver를 포함해 모든 provider가 조회된다`() {
            // google은 provider 상세를 스프링 기본값(CommonOAuth2Provider)에서 받는다.
            // of()는 비어 있는 필수 값마다 OAUTH_MISCONFIGURED를 던지므로, 예외 없이 돌아온
            // 것 자체가 필수 설정이 전부 채워졌다는 뜻이다.
            OAuthProvider.entries.forEach { provider ->
                // when
                val registration = oAuthRegistrations.of(provider)

                // then
                assertThat(registration.clientId).isEqualTo("test-client-id")
                assertThat(registration.redirectUri).isEqualTo("http://localhost:3000/login/oauth2/code/${provider.key}")
            }
        }
    }

    @Nested
    @DisplayName("등록되지 않은 provider를 물을 때")
    inner class ReadUnregistered {

        @Test
        fun `PROVIDER_NOT_SUPPORTED가 난다`() {
            // 테스트 설정에는 enum의 세 provider가 전부 등록돼 있어, 공유 컨텍스트의 빈으로는
            // 이 분기에 닿을 수 없다. 등록이 없는 저장소를 물려 같은 코드 경로를 확인한다.
            // given
            val emptyRegistrations = OAuthRegistrations(
                clientRegistrationRepository = ClientRegistrationRepository { null },
                redirectUriResolver = redirectUriResolver,
            )

            // when & then
            assertThatThrownBy { emptyRegistrations.of(OAuthProvider.GOOGLE) }
                .isInstanceOf(OAuthException::class.java)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.PROVIDER_NOT_SUPPORTED)
        }
    }
}
