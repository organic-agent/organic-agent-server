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

) : BaseEntity() {

    companion object {

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
            at: ZonedDateTime,
        ): Gallery {
            requireDeadlineNotPassed(selectionDeadline, at)
            return Gallery(studioId = studioId, title = title, selectionDeadline = selectionDeadline)
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
}
