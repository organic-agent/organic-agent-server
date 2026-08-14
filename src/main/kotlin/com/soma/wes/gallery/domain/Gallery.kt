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

    /**
     * 부부가 최종적으로 고를 사진 장수. 계약에서 나오는 값이라 작가만 정한다.
     *
     * null은 "제한 없음"이다 — 장수를 정하지 않고 진행하는 계약도 있고, 이 컬럼이 생기기 전에
     * 만들어진 갤러리에는 넣어줄 값이 없다.
     *
     * 선택 앨범이 아니라 갤러리가 들고 있다. 앨범이 만들어질 때 사본을 뜨면, 작가가 뒤늦게
     * 장수를 고쳐도 앨범은 옛 값으로 막거나 열어준다.
     */
    @Column(name = "max_selectable_photo_count")
    var maxSelectablePhotoCount: Int? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val isVisibleToMember: Boolean
        get() = status != GalleryStatus.DRAFT

    fun isDeadlinePassed(at: ZonedDateTime): Boolean =
        selectionDeadline?.isBefore(at) ?: false

    fun rename(title: String) {
        this.title = title
    }

    /**
     * 계약 장수를 정하거나 바꾼다. null로 되돌리면 제한이 없어진다.
     *
     * 이미 고른 장수보다 작은 값도 받는다. 계약이 줄어드는 일은 실제로 있고, 그때 부부에게
     * 필요한 것은 "지금 몇 장이 넘쳤는지"를 보여주는 화면이지 작가 쪽의 400이 아니다.
     * 넘친 상태에서 더 담는 것은 [com.soma.wes.selection.domain.PhotoSelection]이 막는다.
     */
    fun changeMaxSelectablePhotoCount(maxSelectablePhotoCount: Int?) {
        requireValidMaxSelectablePhotoCount(maxSelectablePhotoCount)

        this.maxSelectablePhotoCount = maxSelectablePhotoCount
    }

    fun changeSelectionDeadline(selectionDeadline: ZonedDateTime?, at: ZonedDateTime) {
        requireDeadlineNotPassed(selectionDeadline, at)

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
        requireDeadlineNotPassed(selectionDeadline, at)

        status = GalleryStatus.OPEN
        this.selectionDeadline = selectionDeadline
    }

    companion object {

        /** 0장짜리 계약은 없다. 장수를 정하지 않는 계약은 null로 둔다. */
        const val MIN_SELECTABLE_PHOTO_COUNT = 1
        const val MAX_TITLE_LENGTH = 100

        /**
         * 새로 만드는 갤러리.
         *
         * 기한 검증이 생성자가 아니라 여기 있다. JPA가 DB에서 되살릴 때는 이미 지나간 기한도
         * 그대로 실어야 해서, 생성자에 두면 마감된 갤러리를 읽는 것 자체가 실패한다.
         */
        fun create(
            studioId: Long,
            title: String,
            selectionDeadline: ZonedDateTime?,
            maxSelectablePhotoCount: Int?,
            at: ZonedDateTime,
        ): Gallery {
            requireValidTitle(title)
            requireDeadlineNotPassed(selectionDeadline, at)
            requireValidMaxSelectablePhotoCount(maxSelectablePhotoCount)
            return Gallery(
                studioId = studioId,
                title = title,
                selectionDeadline = selectionDeadline,
                maxSelectablePhotoCount = maxSelectablePhotoCount,
            )
        }

        /**
         * 장수가 들어오는 두 곳([create]·[changeMaxSelectablePhotoCount])이 모두 쓴다.
         *
         * 0이나 음수를 받으면 부부가 한 장도 고를 수 없는 갤러리가 조용히 만들어지고, 부부는
         * 이유를 알 수 없는 400만 본다. null은 "제한 없음"이라 막지 않는다.
         */
        private fun requireValidMaxSelectablePhotoCount(maxSelectablePhotoCount: Int?) {
            if (maxSelectablePhotoCount != null && maxSelectablePhotoCount < MIN_SELECTABLE_PHOTO_COUNT) {
                throw GalleryException(GalleryErrorCode.INVALID_MAX_SELECTABLE_PHOTO_COUNT)
            }
        }

        /**
         * 컨트롤러의 `@Valid`와 겹치지만 남겨 둔다. bean validation은 컨트롤러를 지나는 요청에만
         * 돌아서, 서비스가 직접 제목을 정해 넣는 경로는 이 문이 유일한 검증이다.
         */
        private fun requireValidTitle(title: String) {
            require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) {
                "갤러리 제목은 비어 있을 수 없고 $MAX_TITLE_LENGTH 자 이하여야 합니다."
            }
        }

        /**
         * 기한이 들어오는 세 곳([create]·[reopen]·[changeSelectionDeadline])이 모두 쓴다.
         * 판단 기준은 [isDeadlinePassed]와 같다.
         *
         * 지난 기한을 그대로 받으면 열려 있는데 아무도 못 고르는 갤러리가 조용히 만들어지고,
         * 부부는 이유를 알 수 없는 403만 본다. 작가가 실수를 알아챌 수 있는 유일한 지점이
         * 값을 넣는 순간이다. null은 "기한 없음"이라 막지 않는다.
         *
         * 세 곳을 빠짐없이 덮는 것이 중요하다. 하나라도 검증 없는 setter로 남겨두면 나중에
         * "기한 연장" 같은 기능이 그 문으로 들어와 규칙을 우회한다.
         */
        private fun requireDeadlineNotPassed(selectionDeadline: ZonedDateTime?, at: ZonedDateTime) {
            if (selectionDeadline != null && selectionDeadline.isBefore(at)) {
                throw GalleryException(GalleryErrorCode.INVALID_SELECTION_DEADLINE)
            }
        }
    }
}
