package com.soma.wes.gallery.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime


@Entity
@Table(
    name = "gallery_invites",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_gallery_invites_token", columnNames = ["token"]),
    ],
    indexes = [
        Index(name = "idx_gallery_invites_gallery_id", columnList = "gallery_id"),
    ],
)
class GalleryInvite(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    /** 링크에 실리는 값. 추측할 수 없어야 하므로 생성은 `SecureTokenGenerator`가 맡는다. */
    @Column(nullable = false, updatable = false, length = 255)
    val token: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    val kind: GalleryInviteKind = GalleryInviteKind.GALLERY_MEMBER,

    @Column(name = "max_uses", nullable = false, updatable = false)
    val maxUses: Int = 2,

    @Column(name = "used_count", nullable = false)
    var usedCount: Int = 0,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: ZonedDateTime,

    /** 작가가 링크를 거둬들인 시각. 링크가 엉뚱한 곳에 퍼졌을 때 쓴다. */
    @Column(name = "revoked_at")
    var revokedAt: ZonedDateTime? = null,

    /** 실제 발급자. 이전 링크나 탈퇴한 발급자는 null일 수 있다. */
    @Column(name = "issued_by_user_id", updatable = false)
    val issuedByUserId: Long? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 초대입니다." }

    val isRevoked: Boolean
        get() = revokedAt != null

    fun isExpiredAt(at: ZonedDateTime): Boolean = !expiresAt.isAfter(at)

    val isFull: Boolean
        get() = usedCount >= maxUses

    fun isUsableAt(at: ZonedDateTime): Boolean = !isRevoked && !isExpiredAt(at) && !isFull

    fun statusAt(at: ZonedDateTime): GalleryInviteStatus = when {
        isRevoked -> GalleryInviteStatus.REVOKED
        isExpiredAt(at) -> GalleryInviteStatus.EXPIRED
        isFull -> GalleryInviteStatus.FULL
        else -> GalleryInviteStatus.ACTIVE
    }

    fun revoke(at: ZonedDateTime) {
        if (isRevoked) {
            return
        }
        revokedAt = at
    }

    fun extendExpiry(expiresAt: ZonedDateTime) {
        this.expiresAt = expiresAt
    }

    fun consume() {
        if (isFull) {
            throw IllegalStateException("초대 사용 횟수를 초과했습니다.")
        }
        usedCount += 1
    }
}
