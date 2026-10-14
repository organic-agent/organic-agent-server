package com.soma.wes.auth.token.storage

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

/**
 * 사용자당 refresh token 한 개만 유지한다. 재발급하면 이전 토큰은 덮어써져 더 이상 쓸 수 없다.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity(

    @Id
    @Column(name = "user_id")
    val userId: Long,

    @Column(nullable = false, length = 512)
    var token: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: ZonedDateTime,

) : BaseEntity() {

    fun renew(token: String, expiresAt: ZonedDateTime) {
        this.token = token
        this.expiresAt = expiresAt
    }

    fun isExpired(now: ZonedDateTime): Boolean = expiresAt.isBefore(now)
}
