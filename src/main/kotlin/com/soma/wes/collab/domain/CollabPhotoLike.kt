package com.soma.wes.collab.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.time.ZonedDateTime


/**
 * 하객이 사진에 누른 좋아요. 행의 존재가 곧 좋아요라 값이 될 열이 따로 없다.
 *
 * 활성 행의 (collab_session_id, photo_id, participant_id) 부분 유니크가 같은 참여자의
 * 중복 좋아요를 막는다. 관리자 휴지통으로 soft delete된 행은 제외해 다시
 * 좋아요를 누를 수 있게 한다.
 */
@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "collab_photo_likes",
    indexes = [
        Index(name = "idx_collab_photo_likes_session_photo", columnList = "collab_session_id, photo_id"),
    ],
)
class CollabPhotoLike(

    @Column(name = "collab_session_id", nullable = false, updatable = false)
    val collabSessionId: Long,

    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

    @Column(name = "participant_id", nullable = false, updatable = false)
    val participantId: Long,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 관리자 7일 휴지통에 든 좋아요는 제품 집계와 하객의 liked 상태에서 제외한다. */
    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null
        protected set

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 좋아요입니다." }
}
