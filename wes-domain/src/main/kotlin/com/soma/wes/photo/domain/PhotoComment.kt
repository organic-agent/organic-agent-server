package com.soma.wes.photo.domain

import com.soma.wes.global.BaseEntity
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.time.ZonedDateTime

/** 계정으로 작성하는 부부의 내부 댓글. 하객 세션의 수명이나 공유 범위에 종속되지 않는다. */
@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "photo_comments",
    indexes = [Index(name = "idx_photo_comments_photo_id_id", columnList = "photo_id, id")],
)
class PhotoComment(
    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

    @Column(name = "author_id", nullable = false, updatable = false)
    val authorId: Long,

    @Column(name = "content", nullable = false, updatable = false, length = MAX_CONTENT_LENGTH)
    val content: String,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 사진 댓글입니다." }

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null
        protected set

    fun deleteBy(userId: Long, at: ZonedDateTime) {
        if (authorId != userId) {
            throw PhotoException(PhotoErrorCode.COMMENT_DELETE_DENIED)
        }
        deletedAt = at
    }

    companion object {
        /** 사진별 대화 입력의 최대 길이. HTTP 검증과 DB 컬럼도 같은 상한을 쓴다. */
        const val MAX_CONTENT_LENGTH = 500

        fun of(photoId: Long, authorId: Long, content: String): PhotoComment {
            val trimmed = content.trim()
            if (trimmed.isEmpty() || content.length > MAX_CONTENT_LENGTH) {
                throw PhotoException(PhotoErrorCode.INVALID_COMMENT)
            }
            return PhotoComment(photoId = photoId, authorId = authorId, content = trimmed)
        }
    }
}
