package com.soma.wes.auth.support

import com.soma.wes.auth.config.JwtProperties
import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.domain.RefreshTokenEntity
import com.soma.wes.auth.domain.RefreshTokenMatch
import com.soma.wes.auth.domain.Subject
import com.soma.wes.auth.repository.RefreshTokenRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 발급한 refresh token을 서버가 기억한다.
 *
 * JWT는 서명만 맞으면 유효하므로, 저장소 없이는 로그아웃이나 탈취 대응이 불가능하다.
 * 저장된 값과 대조해야 "우리가 마지막으로 발급한 토큰"만 재발급에 쓸 수 있다.
 * 토큰 문자열은 남기지 않고 해시([RefreshToken.hashed])만 남긴다.
 */
@Component
class RefreshTokenStore(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtProperties: JwtProperties,
    private val clock: Clock,
) {

    /**
     * @param familyId 이 토큰이 속한 로그인 묶음. 새 로그인이면 새 값, 재발급이면 요청 토큰의 값을 넘긴다.
     */
    @Transactional
    fun save(subject: Subject, refreshToken: RefreshToken, familyId: String): RefreshToken {
        val userId = subject.toUserId()
        val now = ZonedDateTime.now(clock)
        val expiresAt = now.plus(jwtProperties.refreshTokenExpiration)

        val entity = refreshTokenRepository.findByIdOrNull(userId)
        if (entity == null) {
            refreshTokenRepository.save(
                RefreshTokenEntity(
                    userId = userId,
                    tokenHash = refreshToken.hashed(),
                    familyId = familyId,
                    issuedAt = now,
                    expiresAt = expiresAt,
                ),
            )
        } else {
            entity.renew(refreshToken.hashed(), familyId, issuedAt = now, expiresAt = expiresAt)
        }
        return refreshToken
    }

    /**
     * 요청 토큰을 저장된 토큰과 견준다. 행을 잠그므로 호출자는 같은 트랜잭션에서 교체([save])나 폐기([delete])까지 끝낸다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun matchForUpdate(subject: Subject, requested: RefreshToken, requestedFamilyId: String?): RefreshTokenMatch =
        refreshTokenRepository.findWithLockByUserId(subject.toUserId())
            ?.match(requested.hashed(), requestedFamilyId, ZonedDateTime.now(clock))
            ?: RefreshTokenMatch.MISSING

    @Transactional
    fun delete(subject: Subject) {
        refreshTokenRepository.deleteById(subject.toUserId())
    }
}
