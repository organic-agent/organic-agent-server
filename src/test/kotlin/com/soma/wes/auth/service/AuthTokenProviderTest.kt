package com.soma.wes.auth.service

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.user.domain.Role
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AuthTokenProviderTest @Autowired constructor(
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
) {

    private fun signUp(providerId: String) = userRepository.save(
        User(provider = OAuthProvider.KAKAO, providerId = providerId, nickname = "테스터"),
    )

    @Test
    fun `access token에서 인증 주체를 복원한다`() {
        val user = signUp("kakao-auth-1")

        val accessToken = authTokenProvider.generateAccessToken(user)
        val principal = authTokenProvider.getAuthUser(accessToken).principal as LoginUser

        assertEquals(user.id, principal.id)
        assertEquals("kakao-auth-1", principal.providerId)
    }

    @Test
    fun `기본 권한은 USER다`() {
        val user = signUp("kakao-role-1")

        assertEquals(Role.USER, user.role)

        val accessToken = authTokenProvider.generateAccessToken(user)
        val authentication = authTokenProvider.getAuthUser(accessToken)

        assertEquals("ROLE_USER", authentication.authorities.single().authority)
    }

    @Test
    fun `ADMIN 권한이 토큰에 실려 복원된다`() {
        val user = signUp("kakao-role-2")
        user.changeRole(Role.ADMIN)

        val accessToken = authTokenProvider.generateAccessToken(user)
        val authentication = authTokenProvider.getAuthUser(accessToken)

        assertEquals("ROLE_ADMIN", authentication.authorities.single().authority)
    }

    @Test
    fun `refresh token은 access token으로 쓸 수 없다`() {
        val user = signUp("kakao-auth-2")
        val refreshToken = authTokenProvider.generateRefreshToken(user)

        val exception = assertFailsWith<TokenException> {
            authTokenProvider.getAuthUser(AccessToken(refreshToken.value))
        }
        assertEquals(AuthErrorCode.TOKEN_TYPE_MISMATCH, exception.errorCode)
    }

    @Test
    fun `형식이 깨진 토큰은 TOKEN_INVALID로 거부한다`() {
        val exception = assertFailsWith<TokenException> {
            authTokenProvider.getAuthUser(AccessToken("not-a-jwt"))
        }
        assertEquals(AuthErrorCode.TOKEN_INVALID, exception.errorCode)
    }

    @Test
    fun `방금 발급한 refresh token은 유효하다`() {
        val user = signUp("kakao-auth-3")

        val refreshToken = authTokenProvider.generateRefreshToken(user)

        assertTrue(authTokenProvider.isValidRefreshToken(refreshToken))
    }

    @Test
    fun `위조된 refresh token은 유효하지 않다`() {
        assertFalse(authTokenProvider.isValidRefreshToken(RefreshToken("not-a-jwt")))
    }

    @Test
    fun `로그아웃하면 refresh token이 무효가 된다`() {
        val user = signUp("kakao-auth-4")
        val refreshToken = authTokenProvider.generateRefreshToken(user)

        authTokenProvider.logout(user)

        assertFalse(authTokenProvider.isValidRefreshToken(refreshToken))
    }

    @Test
    fun `refresh token으로 사용자를 조회한다`() {
        val user = signUp("kakao-auth-5")
        val refreshToken = authTokenProvider.generateRefreshToken(user)

        assertEquals(user.id, authTokenProvider.parseUser(refreshToken).id)
    }
}
