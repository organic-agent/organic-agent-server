package com.soma.wes.auth.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.ZonedDateTime

/**
 * 사용자당 refresh token 한 개만 유지한다. 재발급하면 이전 토큰은 덮어써져 더 이상 쓸 수 없다.
 *
 * 토큰 자체가 아니라 해시([RefreshToken.hashed])를 저장한다. DB가 새도 저장된 값으로는 재발급을 받을 수 없다.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity(

    @Id
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    tokenHash: String,

    familyId: String,

    issuedAt: ZonedDateTime,

    expiresAt: ZonedDateTime,

) : BaseEntity() {

    @Column(name = "token_hash", nullable = false, length = TOKEN_HASH_LENGTH)
    var tokenHash: String = tokenHash
        protected set

    /**
     * 이 토큰을 만든 로그인 묶음. 재발급은 묶음을 잇고, 새 로그인은 새 묶음을 만든다.
     * V54 이전에 발급돼 묶음을 모르는 행만 NULL이다.
     */
    @Column(name = "family_id", nullable = true, length = FAMILY_ID_LENGTH)
    var familyId: String? = familyId
        protected set

    @Column(name = "issued_at", nullable = false)
    var issuedAt: ZonedDateTime = issuedAt
        protected set

    @Column(name = "expires_at", nullable = false)
    var expiresAt: ZonedDateTime = expiresAt
        protected set

    fun renew(tokenHash: String, familyId: String, issuedAt: ZonedDateTime, expiresAt: ZonedDateTime) {
        this.tokenHash = tokenHash
        this.familyId = familyId
        this.issuedAt = issuedAt
        this.expiresAt = expiresAt
    }

    fun isExpired(now: ZonedDateTime): Boolean = expiresAt.isBefore(now)

    /**
     * 재발급 요청에 온 토큰을 이 행과 견준다. 무엇을 받아 주고 무엇을 끊는지는 [RefreshTokenMatch]의 각 값에 적었다.
     *
     * @param requestedFamilyId 요청 토큰에 담긴 로그인 묶음. V54 이전에 발급된 토큰이면 null이다.
     */
    fun match(requestedTokenHash: String, requestedFamilyId: String?, now: ZonedDateTime): RefreshTokenMatch = when {
        isExpired(now) -> RefreshTokenMatch.MISSING
        // 해시끼리의 비교라 시간차 공격을 걱정할 값이 아니다 — 요청자는 해시의 앞자리를 고를 수 없다.
        tokenHash == requestedTokenHash -> RefreshTokenMatch.MATCH
        requestedFamilyId == null || requestedFamilyId != familyId -> RefreshTokenMatch.SUPERSEDED
        now.isBefore(issuedAt.plus(REUSE_GRACE)) -> RefreshTokenMatch.RACED
        else -> RefreshTokenMatch.REUSED
    }

    companion object {
        /** SHA-256을 16진수로 인코딩한 길이. */
        const val TOKEN_HASH_LENGTH = 64

        /** UUID 문자열 길이. */
        const val FAMILY_ID_LENGTH = 36

        /**
         * 교체 직후 같은 묶음의 옛 토큰을 경쟁([RefreshTokenMatch.RACED])으로 봐 주는 시간.
         * 탭 사이의 재발급 경쟁은 수 초 안에 끝난다. 이 시간 안에 탈취자가 옛 토큰을 쓰더라도 거절은 된다 — 끊지 않을 뿐이다.
         */
        val REUSE_GRACE: Duration = Duration.ofSeconds(30)
    }
}
