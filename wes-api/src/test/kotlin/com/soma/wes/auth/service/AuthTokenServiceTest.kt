package com.soma.wes.auth.service

import com.soma.wes.auth.config.JwtProperties
import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.domain.RefreshTokenEntity
import com.soma.wes.auth.domain.Subject
import com.soma.wes.auth.domain.TokenType
import com.soma.wes.auth.dto.request.ReissueRequest
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.auth.service.port.TokenSigner
import com.soma.wes.auth.support.RefreshTokenStore
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

@IntegrationTest
class AuthTokenServiceTest @Autowired constructor(
    private val authTokenService: AuthTokenService,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val tokenSigner: TokenSigner,
    private val jwtProperties: JwtProperties,
    private val refreshTokenStore: RefreshTokenStore,
    private val jdbcTemplate: JdbcTemplate,
) {

    private fun signUp(providerId: String) = userRepository.save(
        User(provider = OAuthProvider.KAKAO, providerId = providerId, nickname = "테스터"),
    )

    private fun reissue(refreshToken: RefreshToken): RefreshToken =
        RefreshToken(authTokenService.reissue(ReissueRequest(refreshToken.value)).refreshToken)

    private fun assertRejected(refreshToken: RefreshToken) {
        assertThatThrownBy { authTokenService.reissue(ReissueRequest(refreshToken.value)) }
            .isInstanceOf(TokenException::class.java)
            .extracting("errorCode")
            .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID)
    }

    /** 저장된 토큰을 [RefreshTokenEntity.REUSE_GRACE]보다 오래 전에 발급한 것으로 만든다. */
    private fun ageStoredToken(user: User) {
        jdbcTemplate.update(
            "UPDATE refresh_tokens SET issued_at = issued_at - interval '1 minute' WHERE user_id = ?",
            user.requiredId,
        )
    }

    private fun storedTokenCount(user: User): Int = checkNotNull(
        jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Int::class.java, user.requiredId),
    ) { "count(*)는 null을 돌려주지 않는다" }

    @Test
    fun `로그아웃하면 저장된 refresh token이 무효가 된다`() {
        val user = signUp("kakao-logout")
        val refreshToken = authTokenProvider.generateRefreshToken(user)

        authTokenService.logout(user.requiredId)

        assertThatThrownBy { authTokenService.reissue(ReissueRequest(refreshToken.value)) }
            .isInstanceOf(TokenException::class.java)
            .extracting("errorCode")
            .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID)
    }

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
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID)
        }

        @Test
        fun `위조된 refresh token은 거부한다`() {
            // when & then
            assertThatThrownBy { authTokenService.reissue(ReissueRequest("not-a-jwt")) }
                .isInstanceOf(TokenException::class.java)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID)
        }
    }

    @Nested
    @DisplayName("교체된 토큰이 다시 올 때")
    inner class ReuseDetection {

        @Test
        fun `같은 로그인에서 교체된 토큰이 유예 시간 뒤에 다시 오면 그 로그인을 끊는다`() {
            // given
            val user = signUp("kakao-reuse-1")
            val stolen = authTokenProvider.generateRefreshToken(user)
            val latest = reissue(stolen)
            ageStoredToken(user)

            // when
            assertRejected(stolen)

            // then
            // 누가 최신 토큰을 들고 있는지 알 수 없으므로 최신 토큰도 함께 끊긴다.
            assertThat(storedTokenCount(user)).isZero()
            assertRejected(latest)
        }

        @Test
        fun `교체 직후 다른 탭이 옛 토큰을 들고 오면 거절만 하고 새 토큰은 살린다`() {
            // given
            val user = signUp("kakao-reuse-2")
            val old = authTokenProvider.generateRefreshToken(user)
            val latest = reissue(old)

            // when
            assertRejected(old)

            // then
            // 웹은 거절을 받으면 다른 탭이 저장한 최신 토큰으로 다시 시도한다. 그 재시도가 통해야 한다.
            reissue(latest)
        }

        @Test
        fun `다른 기기에서 다시 로그인해 갈린 옛 토큰은 거절만 한다`() {
            // given
            val user = signUp("kakao-reuse-3")
            val laptop = authTokenProvider.generateRefreshToken(user)
            val phone = authTokenProvider.generateRefreshToken(user)
            ageStoredToken(user)

            // when
            assertRejected(laptop)

            // then
            // 로그인이 다르면 정상 흐름이라 폰의 로그인은 그대로다.
            reissue(phone)
        }

        @Test
        fun `로그인 묶음이 없는 V54 이전 토큰도 재발급되고 새 묶음을 받는다`() {
            // given
            val user = signUp("kakao-reuse-4")
            val legacy = legacyRefreshToken(user)

            // when
            val rotated = reissue(legacy)

            // then
            assertThat(tokenSigner.verify(rotated.value)["fam"]).isNotNull()
            val storedFamily = jdbcTemplate.queryForObject(
                "SELECT family_id FROM refresh_tokens WHERE user_id = ?",
                String::class.java,
                user.requiredId,
            )
            assertThat(storedFamily).isEqualTo(tokenSigner.verify(rotated.value)["fam"])
        }

        @Test
        fun `로그인 묶음이 없는 옛 토큰은 갈린 뒤 다시 와도 거절만 한다`() {
            // given
            val user = signUp("kakao-reuse-5")
            val legacy = legacyRefreshToken(user)
            val latest = authTokenProvider.generateRefreshToken(user)
            ageStoredToken(user)

            // when
            assertRejected(legacy)

            // then
            // 묶음을 모르면 재사용인지 가를 수 없어 로그인을 끊지 않는다.
            reissue(latest)
        }

        /** V54 이전 형식: 묶음 클레임이 없고, 저장된 행의 묶음도 NULL이다(마이그레이션이 남긴 모양). */
        private fun legacyRefreshToken(user: User): RefreshToken {
            val subject = Subject.from(user.requiredId)
            val token = RefreshToken(
                tokenSigner.sign(
                    subject = subject,
                    expiresIn = jwtProperties.refreshTokenExpiration,
                    claims = mapOf("type" to TokenType.REFRESH.name),
                ),
            )
            refreshTokenStore.save(subject, token, familyId = "legacy")
            jdbcTemplate.update("UPDATE refresh_tokens SET family_id = NULL WHERE user_id = ?", user.requiredId)
            return token
        }
    }
}
