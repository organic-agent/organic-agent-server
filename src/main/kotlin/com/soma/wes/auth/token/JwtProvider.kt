package com.soma.wes.auth.token

import com.soma.wes.auth.domain.Subject
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.auth.token.config.JwtProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.security.SignatureException
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Date
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * JWT를 서명하고 파싱하는 도구.
 *
 * "어떤 종류의 토큰을 얼마나 살려둘 것인가", "어떤 클레임을 담을 것인가" 같은 정책은 모른다.
 * 그건 [com.soma.wes.auth.service.AuthTokenProvider]가 정한다.
 */
@Component
class JwtProvider(
    jwtProperties: JwtProperties,
) {

    private val secretKey = SecretKeySpec(jwtProperties.secret.toByteArray(), "HmacSHA256")

    fun generateToken(
        subject: Subject,
        expiresIn: Duration,
        claims: Map<String, String>,
    ): String {
        val now = Date()
        return Jwts.builder()
            .subject(subject.value)
            // jti가 없으면 같은 초에 발급된 토큰끼리 문자열이 완전히 같아진다.
            // 그러면 재로그인해도 이전 refresh token이 그대로 유효해져 무효화가 성립하지 않는다.
            .id(UUID.randomUUID().toString())
            .claims(claims)
            .issuedAt(now)
            .expiration(Date(now.time + expiresIn.toMillis()))
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    /**
     * 서명과 만료를 검증하고 클레임을 돌려준다. 실패하면 [TokenException]을 던진다.
     */
    fun parseClaims(token: String): Claims =
        try {
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: ExpiredJwtException) {
            throw TokenException(AuthErrorCode.TOKEN_EXPIRED)
        } catch (e: SignatureException) {
            throw TokenException(AuthErrorCode.TOKEN_NOT_SIGNED)
        } catch (e: MalformedJwtException) {
            throw TokenException(AuthErrorCode.TOKEN_INVALID)
        } catch (e: IllegalArgumentException) {
            throw TokenException(AuthErrorCode.TOKEN_EMPTY)
        } catch (e: JwtException) {
            throw TokenException(AuthErrorCode.TOKEN_INVALID)
        }
}
