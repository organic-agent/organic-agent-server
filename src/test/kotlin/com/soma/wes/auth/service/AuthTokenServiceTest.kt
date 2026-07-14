package com.soma.wes.auth.service

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

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

    @Test
    fun `유효한 refresh token으로 access token을 재발급한다`() {
        val user = signUp("kakao-reissue-1")
        val refreshToken = authTokenProvider.generateRefreshToken(user)

        val response = authTokenService.reissue(refreshToken)

        // 재발급된 토큰이 실제로 인증에 쓸 수 있는지까지 확인한다.
        val authentication = authTokenProvider.getAuthUser(AccessToken(response.accessToken))
        assertEquals(user.id, (authentication.principal as LoginUser).id)
    }

    @Test
    fun `재발급하면 refresh token도 교체되고 이전 것은 무효가 된다`() {
        val user = signUp("kakao-rotate-1")
        val oldRefreshToken = authTokenProvider.generateRefreshToken(user)

        val response = authTokenService.reissue(oldRefreshToken)

        // 새 refresh token은 이전 것과 달라야 하고, 곧바로 다시 재발급에 쓸 수 있어야 한다.
        val newRefreshToken = RefreshToken(response.refreshToken)
        assertNotEquals(oldRefreshToken, newRefreshToken)
        authTokenService.reissue(newRefreshToken)

        // 한 번 쓴 refresh token은 재사용할 수 없다.
        val exception = assertFailsWith<TokenException> {
            authTokenService.reissue(oldRefreshToken)
        }
        assertEquals(AuthErrorCode.REFRESH_TOKEN_INVALID, exception.errorCode)
    }

    @Test
    fun `저장소에 없는 refresh token은 거부한다`() {
        val user = signUp("kakao-reissue-2")
        val oldRefreshToken = authTokenProvider.generateRefreshToken(user)

        // 재로그인하면 저장소의 토큰이 갈린다. 이전 토큰은 서명이 유효해도 더 이상 쓸 수 없어야 한다.
        authTokenProvider.generateRefreshToken(user)

        val exception = assertFailsWith<TokenException> {
            authTokenService.reissue(oldRefreshToken)
        }
        assertEquals(AuthErrorCode.REFRESH_TOKEN_INVALID, exception.errorCode)
    }

    @Test
    fun `access token으로는 재발급할 수 없다`() {
        val user = signUp("kakao-reissue-3")
        val accessToken = authTokenProvider.generateAccessToken(user)

        assertFailsWith<TokenException> {
            authTokenService.reissue(RefreshToken(accessToken.value))
        }
    }

    @Test
    fun `위조된 refresh token은 거부한다`() {
        assertFailsWith<TokenException> {
            authTokenService.reissue(RefreshToken("not-a-jwt"))
        }
    }
}
