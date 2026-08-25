package com.soma.wes.admin.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.ZonedDateTime

@Entity
@Table(name = "admin_sessions")
class AdminSession private constructor(

    @Id
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    val tokenHash: String,

    @Column(name = "admin_id", nullable = false, updatable = false)
    val adminId: Long,

    @Column(name = "last_active_at", nullable = false)
    var lastActiveAt: ZonedDateTime,

    @Column(name = "absolute_expires_at", nullable = false, updatable = false)
    val absoluteExpiresAt: ZonedDateTime,

    @Column(name = "revoked_at")
    var revokedAt: ZonedDateTime?,

) : BaseEntity() {

    fun isUsable(
        now: ZonedDateTime,
        idleTtl: Duration,
    ): Boolean =
        revokedAt == null &&
            absoluteExpiresAt.isAfter(now) &&
            lastActiveAt.plus(idleTtl).isAfter(now)

    fun touch(now: ZonedDateTime) {
        lastActiveAt = now
    }

    fun revoke(now: ZonedDateTime) {
        revokedAt = now
    }

    companion object {
        fun of(
            tokenHash: String,
            adminId: Long,
            now: ZonedDateTime,
            absoluteTtl: Duration,
        ): AdminSession =
            AdminSession(
                tokenHash = tokenHash,
                adminId = adminId,
                lastActiveAt = now,
                absoluteExpiresAt = now.plus(absoluteTtl),
                revokedAt = null,
            )
    }
}
