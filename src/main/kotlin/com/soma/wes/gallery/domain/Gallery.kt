package com.soma.wes.gallery.domain

import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
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
import java.time.ZonedDateTime


@Entity
@Table(
    name = "galleries",
    indexes = [
        Index(name = "idx_galleries_studio_id", columnList = "studio_id"),
    ],
)
class Gallery(

    @Column(name = "studio_id", nullable = false, updatable = false)
    val studioId: Long,

    @Column(nullable = false, length = 100)
    var title: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: GalleryStatus = GalleryStatus.DRAFT,

    @Column(name = "selection_deadline")
    var selectionDeadline: ZonedDateTime? = null,

    @Column(name = "max_selectable_photo_count")
    var maxSelectablePhotoCount: Int? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 갤러리입니다." }

    val isVisibleToMember: Boolean
        get() = status != GalleryStatus.DRAFT

    fun isDeadlinePassed(at: ZonedDateTime): Boolean =
        selectionDeadline?.isBefore(at) ?: false

    fun rename(title: String) {
        this.title = title
    }

    fun changeMaxSelectablePhotoCount(maxSelectablePhotoCount: Int?) {
        validateMaxSelectablePhotoCount(maxSelectablePhotoCount)

        this.maxSelectablePhotoCount = maxSelectablePhotoCount
    }

    fun changeSelectionDeadline(selectionDeadline: ZonedDateTime?, at: ZonedDateTime) {
        validateDeadlineNotPassed(selectionDeadline, at)

        this.selectionDeadline = selectionDeadline
    }

    fun open() {
        if (status != GalleryStatus.DRAFT) {
            throw GalleryException(GalleryErrorCode.INVALID_STATUS_TRANSITION)
        }
        status = GalleryStatus.OPEN
    }

    fun close() {
        if (status != GalleryStatus.OPEN) {
            throw GalleryException(GalleryErrorCode.INVALID_STATUS_TRANSITION)
        }
        status = GalleryStatus.CLOSED
    }

    fun reopen(selectionDeadline: ZonedDateTime?, at: ZonedDateTime) {
        if (status != GalleryStatus.CLOSED) {
            throw GalleryException(GalleryErrorCode.INVALID_STATUS_TRANSITION)
        }
        validateDeadlineNotPassed(selectionDeadline, at)

        status = GalleryStatus.OPEN
        this.selectionDeadline = selectionDeadline
    }

    companion object {

        /** 0장짜리 계약은 없다. 장수를 정하지 않는 계약은 null로 둔다. */
        const val MIN_SELECTABLE_PHOTO_COUNT = 1
        const val MAX_TITLE_LENGTH = 100

        fun create(
            studioId: Long,
            title: String,
            selectionDeadline: ZonedDateTime?,
            maxSelectablePhotoCount: Int?,
            at: ZonedDateTime,
        ): Gallery {
            validateTitle(title)
            validateDeadlineNotPassed(selectionDeadline, at)
            validateMaxSelectablePhotoCount(maxSelectablePhotoCount)
            return Gallery(
                studioId = studioId,
                title = title,
                selectionDeadline = selectionDeadline,
                maxSelectablePhotoCount = maxSelectablePhotoCount,
            )
        }

        private fun validateMaxSelectablePhotoCount(maxSelectablePhotoCount: Int?) {
            if (maxSelectablePhotoCount != null && maxSelectablePhotoCount < MIN_SELECTABLE_PHOTO_COUNT) {
                throw GalleryException(GalleryErrorCode.INVALID_MAX_SELECTABLE_PHOTO_COUNT)
            }
        }

        /**
         * 컨트롤러의 `@Valid`와 겹치지만 남겨 둔다. bean validation은 컨트롤러를 지나는 요청에만
         * 돌아서, 서비스가 직접 제목을 정해 넣는 경로는 이 문이 유일한 검증이다.
         */
        private fun validateTitle(title: String) {
            require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) {
                "갤러리 제목은 비어 있을 수 없고 $MAX_TITLE_LENGTH 자 이하여야 합니다."
            }
        }

        private fun validateDeadlineNotPassed(selectionDeadline: ZonedDateTime?, at: ZonedDateTime) {
            if (selectionDeadline != null && selectionDeadline.isBefore(at)) {
                throw GalleryException(GalleryErrorCode.INVALID_SELECTION_DEADLINE)
            }
        }
    }
}
