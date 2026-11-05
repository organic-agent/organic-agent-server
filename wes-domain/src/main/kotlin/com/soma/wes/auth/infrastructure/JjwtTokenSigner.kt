package com.soma.wes.auth.infrastructure

import com.soma.wes.auth.domain.Subject
import com.soma.wes.auth.dto.TokenClaimsDto
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.auth.service.port.TokenSigner
import com.soma.wes.auth.config.JwtProperties
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

/** [TokenSigner]의 jjwt 구현. HS256으로 서명한다. */
@Component
class JjwtTokenSigner(
    jwtProperties: JwtProperties,
) : TokenSigner {

    private val secretKey: SecretKeySpec

    init {
        val secretBytes = jwtProperties.secret.toByteArray()
        // HS256은 256비트(32바이트) 이상 키를 요구한다. 짧으면 첫 토큰 발급 때 터지는 대신 기동 시점에 막는다.
        require(secretBytes.size >= MIN_SECRET_BYTES) {
            "jwt.secret은 최소 ${MIN_SECRET_BYTES}바이트여야 합니다 (현재 ${secretBytes.size}바이트)."
        }
        secretKey = SecretKeySpec(secretBytes, "HmacSHA256")
    }

    companion object {
        private const val MIN_SECRET_BYTES = 32
    }

    override fun sign(
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

    override fun verify(token: String): TokenClaimsDto =
        try {
            val payload = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .payload
            TokenClaimsDto(subject = payload.subject, claims = payload.toMap())
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
