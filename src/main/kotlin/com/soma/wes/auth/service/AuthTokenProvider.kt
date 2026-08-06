package com.soma.wes.auth.service

import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.user.domain.Role
import com.soma.wes.auth.domain.Subject
import com.soma.wes.auth.domain.Token
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.auth.token.JwtProvider
import com.soma.wes.auth.token.TokenStorage
import com.soma.wes.auth.token.TokenType
import com.soma.wes.auth.token.config.JwtProperties
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import io.jsonwebtoken.Claims
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Service

/**
 * 토큰 정책을 담당한다.
 *
 * [JwtProvider]가 "JWT를 어떻게 서명하고 파싱하는가"를 안다면, 이 클래스는 그 도구를 써서
 * "access와 refresh를 어떻게 구분하고, 얼마나 살려두고, 무엇을 담고, 무엇을 저장할 것인가"를 정한다.
 */
@Service
class AuthTokenProvider(
    private val jwtProvider: JwtProvider,
    private val jwtProperties: JwtProperties,
    private val tokenStorage: TokenStorage,
    private val userRepository: UserRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TOKEN_TYPE_CLAIM = "type"
        private const val PROVIDER_ID_CLAIM = "providerId"
        private const val ROLE_CLAIM = "role"
    }

    fun generateAccessToken(user: User): AccessToken =
        AccessToken(
            jwtProvider.generateToken(
                subject = Subject.from(requireId(user)),
                expiresIn = jwtProperties.accessTokenExpiration,
                claims = mapOf(
                    TOKEN_TYPE_CLAIM to TokenType.ACCESS.name,
                    // 인증 주체를 복원할 때 DB를 조회하지 않으려고 토큰에 함께 담는다.
                    PROVIDER_ID_CLAIM to user.providerId,
                    ROLE_CLAIM to user.role.name,
                ),
            ),
        )

    /**
     * refresh token은 발급과 동시에 저장소에 기록한다.
     * 같은 사용자가 다시 발급받으면 이전 토큰은 덮어써져 무효가 된다.
     */
    fun generateRefreshToken(user: User): RefreshToken {
        val subject = Subject.from(requireId(user))
        val refreshToken = RefreshToken(
            jwtProvider.generateToken(
                subject = subject,
                expiresIn = jwtProperties.refreshTokenExpiration,
                claims = mapOf(TOKEN_TYPE_CLAIM to TokenType.REFRESH.name),
            ),
        )
        return tokenStorage.save(subject, refreshToken)
    }

    /**
     * 서명·만료·종류가 유효하면서, 서버가 마지막으로 발급한 것과 일치하는 refresh token인지 확인한다.
     */
    fun isValidRefreshToken(requestedRefreshToken: RefreshToken): Boolean {
        val subject = try {
            parseSubject(requestedRefreshToken)
        } catch (e: TokenException) {
            // /reissue에 대한 무효·만료 refresh token 시도를 남겨 남용 탐지에 쓴다.
            log.debug("유효하지 않은 refresh token: {}", e.message)
            return false
        }
        return tokenStorage.find(subject) == requestedRefreshToken
    }

    /**
     * access token에서 인증 주체를 복원한다. DB를 조회하지 않으므로 요청마다 추가 비용이 없다.
     */
    fun getAuthUser(accessToken: AccessToken): Authentication {
        val claims = verifiedClaims(accessToken)

        val role = runCatching { Role.valueOf(claims.requireString(ROLE_CLAIM)) }
            .getOrElse { throw TokenException(AuthErrorCode.TOKEN_INVALID) }
        val authorities = listOf(SimpleGrantedAuthority(role.authority))

        val loginUser = LoginUser(
            id = Subject(claims.subject).toUserId(),
            providerId = claims.requireString(PROVIDER_ID_CLAIM),
            authorities = authorities,
        )
        return UsernamePasswordAuthenticationToken(loginUser, "", authorities)
    }

    /**
     * [getAuthUser]가 쓴다. 우리가 발급한 토큰이라면 반드시 있어야 하는 클레임이다.
     * 없다면 위조됐거나 형식이 바뀐 토큰이므로, 500이 아니라 401로 돌려보낸다.
     */
    private fun Claims.requireString(key: String): String =
        this[key] as? String ?: throw TokenException(AuthErrorCode.TOKEN_INVALID)

    fun parseUser(token: Token): User =
        userRepository.findById(parseSubject(token).toUserId())
            .orElseThrow { TokenException(AuthErrorCode.TOKEN_OWNER_NOT_FOUND) }

    fun parseSubject(token: Token): Subject = Subject(verifiedClaims(token).subject)

    /** [getAuthUser]와 [parseSubject]가 쓴다. */
    private fun verifiedClaims(token: Token): Claims {
        val claims = jwtProvider.parseClaims(token.value)

        // refresh token으로 API를 호출하거나 access token으로 재발급을 시도하는 오용을 막는다.
        if (claims[TOKEN_TYPE_CLAIM] != token.type.name) {
            throw TokenException(AuthErrorCode.TOKEN_TYPE_MISMATCH)
        }
        // subject가 사용자 식별자로 읽히지 않으면 우리가 발급한 토큰이 아니다.
        if (claims.subject?.toLongOrNull() == null) {
            throw TokenException(AuthErrorCode.TOKEN_INVALID)
        }
        return claims
    }

    fun logout(user: User) {
        tokenStorage.delete(Subject.from(requireId(user)))
    }

    /** [generateAccessToken]·[generateRefreshToken]·[logout]이 쓴다. */
    private fun requireId(user: User): Long =
        checkNotNull(user.id) { "저장되지 않은 사용자로는 토큰을 발급할 수 없습니다." }
}
