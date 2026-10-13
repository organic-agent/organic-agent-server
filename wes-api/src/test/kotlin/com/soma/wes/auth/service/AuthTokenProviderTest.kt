package com.soma.wes.auth.service

import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.user.domain.Role
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
class AuthTokenProviderTest @Autowired constructor(
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
) {

    private fun signUp(providerId: String) = userRepository.save(
        User(provider = OAuthProvider.KAKAO, providerId = providerId, nickname = "테스터"),
    )

    @Nested
    @DisplayName("access token으로 인증할 때")
    inner class AuthenticateWithAccessToken {

        @Test
        fun `access token에서 인증 주체를 복원한다`() {
            // given
            val user = signUp("kakao-auth-1")

            // when
            val accessToken = authTokenProvider.generateAccessToken(user)
            val principal = authTokenProvider.getAuthUser(accessToken).principal as LoginUser

            // then
            assertThat(principal.id).isEqualTo(user.id)
            assertThat(principal.providerId).isEqualTo("kakao-auth-1")
        }

        @Test
        fun `기본 권한은 USER다`() {
            // given
            val user = signUp("kakao-role-1")
            assertThat(user.role).isEqualTo(Role.USER)

            // when
            val accessToken = authTokenProvider.generateAccessToken(user)
            val authentication = authTokenProvider.getAuthUser(accessToken)

            // then
            assertThat(authentication.authorities.single().authority).isEqualTo("ROLE_USER")
        }

        @Test
        fun `ADMIN 권한이 토큰에 실려 복원된다`() {
            // given
            val user = signUp("kakao-role-2")
            user.changeRole(Role.ADMIN)

            // when
            val accessToken = authTokenProvider.generateAccessToken(user)
            val authentication = authTokenProvider.getAuthUser(accessToken)

            // then
            assertThat(authentication.authorities.single().authority).isEqualTo("ROLE_ADMIN")
        }

        @Test
        fun `refresh token은 access token으로 쓸 수 없다`() {
            // given
            val user = signUp("kakao-auth-2")
            val refreshToken = authTokenProvider.generateRefreshToken(user)

            // when & then
            assertThatThrownBy { authTokenProvider.getAuthUser(AccessToken(refreshToken.value)) }
                .isInstanceOf(TokenException::class.java)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.TOKEN_TYPE_MISMATCH)
        }

        @Test
        fun `형식이 깨진 토큰은 TOKEN_INVALID로 거부한다`() {
            // when & then
            assertThatThrownBy { authTokenProvider.getAuthUser(AccessToken("not-a-jwt")) }
                .isInstanceOf(TokenException::class.java)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.TOKEN_INVALID)
        }
    }

    @Nested
    @DisplayName("refresh token을 다룰 때")
    inner class HandleRefreshToken {

        @Test
        fun `방금 발급한 refresh token은 유효하다`() {
            // given
            val user = signUp("kakao-auth-3")

            // when
            val refreshToken = authTokenProvider.generateRefreshToken(user)

            // then
            assertThat(authTokenProvider.isValidRefreshToken(refreshToken)).isTrue()
        }

        @Test
        fun `위조된 refresh token은 유효하지 않다`() {
            // when & then
            assertThat(authTokenProvider.isValidRefreshToken(RefreshToken("not-a-jwt"))).isFalse()
        }

        @Test
        fun `로그아웃하면 refresh token이 무효가 된다`() {
            // given
            val user = signUp("kakao-auth-4")
            val refreshToken = authTokenProvider.generateRefreshToken(user)

            // when
            authTokenProvider.logout(user)

            // then
            assertThat(authTokenProvider.isValidRefreshToken(refreshToken)).isFalse()
        }

        @Test
        fun `refresh token으로 사용자를 조회한다`() {
            // given
            val user = signUp("kakao-auth-5")
            val refreshToken = authTokenProvider.generateRefreshToken(user)

            // when & then
            assertThat(authTokenProvider.parseUser(refreshToken).id).isEqualTo(user.id)
        }
    }
}
