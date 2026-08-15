package com.soma.wes.collab.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint


/**
 * 하객이 사진에 누른 좋아요. 행의 존재가 곧 좋아요라 값이 될 열이 따로 없다.
 *
 * (collab_photo_id, collab_guest_id) 유니크가 이 도메인의 전부다 — 같은 하객이 몇 번을
 * 눌러도 행이 늘지 않아야 "좋아요 40"이 사람 40명으로 남는다.
 */
@Entity
@Table(
    name = "collab_photo_likes",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_collab_photo_likes_photo_guest",
            columnNames = ["collab_photo_id", "collab_guest_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_collab_photo_likes_collab_photo_id", columnList = "collab_photo_id"),
    ],
)
class CollabPhotoLike(

    @Column(name = "collab_photo_id", nullable = false, updatable = false)
    val collabPhotoId: Long,

    @Column(name = "collab_guest_id", nullable = false, updatable = false)
    val collabGuestId: Long,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 좋아요입니다." }
}
