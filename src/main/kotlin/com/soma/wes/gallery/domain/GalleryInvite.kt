package com.soma.wes.gallery.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime

/**
 * 갤러리 초대 링크. 계정이 없는 사람에게도 보낼 수 있다는 점이 [GalleryMember]와 다르다.
 *
 * 한 링크를 여러 명이 쓸 수 있다. 작가가 신랑에게 보내면 신랑이 신부에게 전달하는 흐름이라,
 * 1회용으로 만들면 작가가 연락처도 모르는 신부 몫까지 따로 발급해야 한다.
 * 대신 유출되면 사진 선택 권한까지 넘어가므로 만료를 짧게 잡고 폐기할 수 있게 한다.
 *
 * 상태를 컬럼으로 두지 않는다. "만료됨"은 [expiresAt]에서 계산되는 값이라 따로 저장하면
 * 정각에 도는 작업이 필요하고, 그게 밀린 동안 만료된 링크가 살아 있게 된다.
 * 수락 기록도 두지 않는다 — 누가 들어왔는지는 [GalleryMember] 행이 남긴다.
 */
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

    /** 링크에 실리는 값. 추측할 수 없어야 하므로 생성은 `GalleryInviteTokenGenerator`가 맡는다. */
    @Column(nullable = false, updatable = false, length = 255)
    val token: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: ZonedDateTime,

    /** 작가가 링크를 거둬들인 시각. 링크가 엉뚱한 곳에 퍼졌을 때 쓴다. */
    @Column(name = "revoked_at")
    var revokedAt: ZonedDateTime? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val isRevoked: Boolean
        get() = revokedAt != null

    fun isExpiredAt(at: ZonedDateTime): Boolean = expiresAt.isBefore(at)

    fun isUsableAt(at: ZonedDateTime): Boolean = !isRevoked && !isExpiredAt(at)

    /**
     * 목록 화면이 유효·만료·폐기를 구분해 보여주기 위한 값.
     *
     * 폐기를 만료보다 먼저 본다. 폐기된 링크를 그대로 두면 언젠가 만료 시각도 지나는데,
     * 그때 "만료됨"으로 보이면 작가가 자기가 거둬들인 링크를 재발급해도 되는 것으로 읽는다.
     */
    fun statusAt(at: ZonedDateTime): GalleryInviteStatus = when {
        isRevoked -> GalleryInviteStatus.REVOKED
        isExpiredAt(at) -> GalleryInviteStatus.EXPIRED
        else -> GalleryInviteStatus.ACTIVE
    }

    /**
     * 링크를 거둬들인다. 이미 폐기됐으면 처음 폐기한 시각을 유지하고 아무것도 하지 않는다.
     * 폐기의 목적은 "못 쓰게 만드는 것"이고 그건 이미 이뤄진 상태라, 버튼을 두 번 눌렀다고
     * 실패를 돌려줄 이유가 없다.
     */
    fun revoke(at: ZonedDateTime) {
        if (isRevoked) {
            return
        }
        revokedAt = at
    }

    fun extendExpiry(expiresAt: ZonedDateTime) {
        this.expiresAt = expiresAt
    }
}
