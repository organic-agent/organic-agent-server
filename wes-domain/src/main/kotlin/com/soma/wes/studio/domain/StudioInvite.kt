package com.soma.wes.studio.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

@Entity
@Table(name = "studio_invites")
class StudioInvite(
    @Column(name = "workspace_id", nullable = false, updatable = false)
    val workspaceId: Long,
    @Column(nullable = false, updatable = false, unique = true, length = 255)
    val token: String,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: ZonedDateTime,
    @Column(name = "issued_by_user_id", updatable = false)
    val issuedByUserId: Long? = null,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
    @Column(name = "revoked_at")
    var revokedAt: ZonedDateTime? = null
    @Column(name = "used_count", nullable = false)
    var usedCount: Int = 0

    val requiredId: Long get() = checkNotNull(id)
}
