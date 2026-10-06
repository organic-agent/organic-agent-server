package com.soma.wes.photo.domain

import com.soma.wes.global.BaseEntity
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 사진 한 장에 적어 두는 메모. 셀렉·보정 확인 화면의 "정보" 탭이 쓴다.
 *
 * **사진당 하나다.** [PhotoRating]과 같은 이유로 사람마다 나누지 않고, 참여자가 같은 한 칸을 함께 고치며
 * 마지막에 쓴 사람이 [updatedBy]와 함께 덮어쓴다. 동시에 고치면 나중 저장이 이긴다 — 두 사람이 같은 사진의
 * 메모를 같은 순간에 고치는 일은 드물어 버전 검사로 막지 않았다.
 *
 * 작가에게 가지 않는다. 작가가 보는 요청은 보정 요청([com.soma.wes.retouch.domain.RetouchPhoto.requestText])이다.
 */
@Entity
@Table(
    name = "photo_memos",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_photo_memos_photo_id", columnNames = ["photo_id"]),
    ],
)
class PhotoMemo(

    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

    @Column(nullable = false, length = MAX_CONTENT_LENGTH)
    var content: String,

    /** 마지막으로 메모를 고친 사람. */
    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 같은 사진에 다시 쓰면 덮어쓴다. */
    fun write(content: String, updatedBy: Long) {
        this.content = requireValidContent(content)
        this.updatedBy = updatedBy
    }

    companion object {

        /** 메모 상한. 보정 요청문(`RetouchPhoto.MAX_REQUEST_TEXT_LENGTH`)과 같은 길이다. */
        const val MAX_CONTENT_LENGTH = 2000

        fun of(photoId: Long, content: String, updatedBy: Long) = PhotoMemo(
            photoId = photoId,
            content = requireValidContent(content),
            updatedBy = updatedBy,
        )

        /** 빈 메모는 저장하지 않는다 — 지우기는 DELETE다. 앞뒤 공백은 그대로 둔다(줄바꿈으로 쓴 메모의 모양). */
        private fun requireValidContent(content: String): String {
            if (content.isBlank() || content.length > MAX_CONTENT_LENGTH) {
                throw PhotoException(PhotoErrorCode.INVALID_MEMO)
            }
            return content
        }
    }
}
