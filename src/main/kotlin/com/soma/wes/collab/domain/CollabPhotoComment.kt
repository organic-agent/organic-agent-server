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

/**
 * 하객이 사진 한 장에 남긴 댓글.
 *
 * 수정은 없다. 지우고 다시 쓰면 되는 한 줄짜리 글이고, 수정을 허용하면 부부가 읽고 판단한
 * 내용이 나중에 다른 말로 바뀌어 있을 수 있다.
 *
 * 소프트 삭제도 하지 않는다. 남길 이유가 감사가 아니라 "부부가 보기 싫은 말을 치우는 것"이라,
 * 지웠는데 DB에 남아 있으면 그 목적 자체를 배신한다.
 */
@Entity
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

    companion object {

        /** 한 줄 감상이 아니라 문장이 오간다. 255자로 잡으면 하객이 쓰다 말고 잘린다. */
        const val MAX_CONTENT_LENGTH = 500

        /**
         * 내용이 들어오는 유일한 문. DTO의 `@field:NotBlank`로 두지 않는 이유는
         * [CollabGuest.requireValidNickname]과 같다.
         */
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
