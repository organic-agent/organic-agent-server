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
