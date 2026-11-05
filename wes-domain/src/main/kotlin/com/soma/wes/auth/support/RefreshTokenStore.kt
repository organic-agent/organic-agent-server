package com.soma.wes.auth.support

import com.soma.wes.auth.config.JwtProperties
import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.domain.RefreshTokenEntity
import com.soma.wes.auth.domain.Subject
import com.soma.wes.auth.repository.RefreshTokenRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 발급한 refresh token을 서버가 기억한다.
 *
 * JWT는 서명만 맞으면 유효하므로, 저장소 없이는 로그아웃이나 탈취 대응이 불가능하다.
 * 저장된 값과 대조해야 "우리가 마지막으로 발급한 토큰"만 재발급에 쓸 수 있다.
 */
@Component
class RefreshTokenStore(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtProperties: JwtProperties,
    private val clock: Clock,
) {

    @Transactional
    fun save(subject: Subject, refreshToken: RefreshToken): RefreshToken {
        val userId = subject.toUserId()
        val expiresAt = ZonedDateTime.now(clock).plus(jwtProperties.refreshTokenExpiration)

        val entity = refreshTokenRepository.findByIdOrNull(userId)
        if (entity == null) {
            refreshTokenRepository.save(
                RefreshTokenEntity(userId = userId, token = refreshToken.value, expiresAt = expiresAt),
            )
        } else {
            entity.renew(refreshToken.value, expiresAt)
        }
        return refreshToken
    }

    @Transactional(readOnly = true)
    fun find(subject: Subject): RefreshToken? =
        refreshTokenRepository.findByIdOrNull(subject.toUserId())
            ?.takeUnless { it.isExpired(ZonedDateTime.now(clock)) }
            ?.let { RefreshToken(it.token) }

    @Transactional
    fun delete(subject: Subject) {
        refreshTokenRepository.deleteById(subject.toUserId())
    }
}
