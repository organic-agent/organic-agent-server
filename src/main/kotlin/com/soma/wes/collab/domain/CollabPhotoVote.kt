package com.soma.wes.collab.domain

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

/**
 * 하객 한 사람이 사진 한 장에 남긴 반응.
 *
 * **하객당 한 행이다**(UK on `collab_photo_id, collab_guest_id`). 없으면 새로고침할 때마다
 * 표가 쌓여 "좋아요 40"이 사람 40명이 아니게 되고, 그 수를 보고 사진을 고르는 부부가 속는다.
 * 마음이 바뀌면 새 행이 아니라 [changeReaction]으로 덮어쓴다.
 *
 * [com.soma.wes.photo.domain.PhotoRating]과 반대 방향이다. 별점은 사진당 한 행이라 마지막
 * 사람이 덮어쓰지만(부부와 작가는 같이 고르는 한 팀이다), 하객 반응은 사람 수를 세는 것이
 * 목적이라 사람마다 한 행이어야 한다.
 */
@Entity
@Table(
    name = "collab_photo_votes",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_collab_photo_votes_photo_guest",
            columnNames = ["collab_photo_id", "collab_guest_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_collab_photo_votes_collab_photo_id", columnList = "collab_photo_id"),
    ],
)
class CollabPhotoVote(

    @Column(name = "collab_photo_id", nullable = false, updatable = false)
    val collabPhotoId: Long,

    @Column(name = "collab_guest_id", nullable = false, updatable = false)
    val collabGuestId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var reaction: CollabReaction,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 반응입니다." }

    /** 같은 사진에 다시 반응하면 덮어쓴다. 행이 늘지 않는 것이 이 도메인의 전부다. */
    fun changeReaction(reaction: CollabReaction) {
        this.reaction = reaction
    }
}
