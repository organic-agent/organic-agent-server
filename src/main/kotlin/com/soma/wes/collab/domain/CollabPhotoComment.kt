package com.soma.wes.collab.domain

import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
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


@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "collab_photo_comments",
    indexes = [
        Index(name = "idx_collab_photo_comments_collab_photo_id", columnList = "collab_photo_id"),
    ],
)
class CollabPhotoComment(

    @Column(name = "collab_photo_id", nullable = false, updatable = false)
    val collabPhotoId: Long,

    @Column(name = "collab_guest_id", nullable = false, updatable = false)
    val collabGuestId: Long,

    @Column(nullable = false, length = MAX_CONTENT_LENGTH)
    val content: String,

) : BaseEntity() {

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    companion object {

        const val MAX_CONTENT_LENGTH = 500

        fun requireValidContent(content: String): String {
            val trimmed = content.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_CONTENT_LENGTH) {
                throw CollabException(CollabErrorCode.INVALID_COMMENT)
            }
            return trimmed
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 댓글입니다." }

    fun isWrittenBy(collabGuestId: Long): Boolean = this.collabGuestId == collabGuestId
}
