package com.soma.wes.auth.token.storage

import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.domain.Subject
import com.soma.wes.auth.token.TokenStorage
import com.soma.wes.auth.token.config.JwtProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, Long>

@Repository
class JpaTokenStorage(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtProperties: JwtProperties,
    private val clock: Clock,
) : TokenStorage {

    @Transactional
    override fun save(subject: Subject, refreshToken: RefreshToken): RefreshToken {
        val userId = subject.toUserId()
        val expiresAt = ZonedDateTime.now(clock).plus(jwtProperties.refreshTokenExpiration)

        val entity = refreshTokenRepository.findById(userId).orElse(null)
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
    override fun find(subject: Subject): RefreshToken? =
        refreshTokenRepository.findById(subject.toUserId())
            .orElse(null)
            ?.takeUnless { it.isExpired(ZonedDateTime.now(clock)) }
            ?.let { RefreshToken(it.token) }

    @Transactional
    override fun delete(subject: Subject) {
        refreshTokenRepository.deleteById(subject.toUserId())
    }
}
