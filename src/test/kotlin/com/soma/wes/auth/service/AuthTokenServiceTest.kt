package com.soma.wes.auth.service

import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.dto.request.ReissueRequest
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AuthTokenServiceTest @Autowired constructor(
    private val authTokenService: AuthTokenService,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
) {

    private fun signUp(providerId: String) = userRepository.save(
        User(provider = OAuthProvider.KAKAO, providerId = providerId, nickname = "테스터"),
    )

    @Nested
    @DisplayName("재발급에 성공할 때")
    inner class ReissueSucceeds {

        @Test
        fun `유효한 refresh token으로 access token을 재발급한다`() {
            // given
            val user = signUp("kakao-reissue-1")
            val refreshToken = authTokenProvider.generateRefreshToken(user)

            // when
            val response = authTokenService.reissue(ReissueRequest(refreshToken.value))

            // then
            // 재발급된 토큰이 실제로 인증에 쓸 수 있는지까지 확인한다.
            val authentication = authTokenProvider.getAuthUser(AccessToken(response.accessToken))
            assertThat((authentication.principal as LoginUser).id).isEqualTo(user.id)
        }

        @Test
        fun `재발급하면 refresh token도 교체되고 이전 것은 무효가 된다`() {
            // given
            val user = signUp("kakao-rotate-1")
            val oldRefreshToken = authTokenProvider.generateRefreshToken(user)

            // when
            val response = authTokenService.reissue(ReissueRequest(oldRefreshToken.value))

            // then
            // 새 refresh token은 이전 것과 달라야 하고, 곧바로 다시 재발급에 쓸 수 있어야 한다.
            val newRefreshToken = RefreshToken(response.refreshToken)
            assertThat(newRefreshToken).isNotEqualTo(oldRefreshToken)
            authTokenService.reissue(ReissueRequest(newRefreshToken.value))

            // 한 번 쓴 refresh token은 재사용할 수 없다.
            assertThatThrownBy { authTokenService.reissue(ReissueRequest(oldRefreshToken.value)) }
                .isInstanceOf(TokenException::class.java)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID)
        }
    }

    @Nested
    @DisplayName("재발급을 거부할 때")
    inner class RejectReissue {

        @Test
        fun `저장소에 없는 refresh token은 거부한다`() {
            // given
            val user = signUp("kakao-reissue-2")
            val oldRefreshToken = authTokenProvider.generateRefreshToken(user)

            // 재로그인하면 저장소의 토큰이 갈린다. 이전 토큰은 서명이 유효해도 더 이상 쓸 수 없어야 한다.
            authTokenProvider.generateRefreshToken(user)

            // when & then
            assertThatThrownBy { authTokenService.reissue(ReissueRequest(oldRefreshToken.value)) }
                .isInstanceOf(TokenException::class.java)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID)
        }

        @Test
        fun `access token으로는 재발급할 수 없다`() {
            // given
            val user = signUp("kakao-reissue-3")
            val accessToken = authTokenProvider.generateAccessToken(user)

            // when & then
            assertThatThrownBy { authTokenService.reissue(ReissueRequest(accessToken.value)) }
                .isInstanceOf(TokenException::class.java)
        }

        @Test
        fun `위조된 refresh token은 거부한다`() {
            // when & then
            assertThatThrownBy { authTokenService.reissue(ReissueRequest("not-a-jwt")) }
                .isInstanceOf(TokenException::class.java)
        }
    }
}
