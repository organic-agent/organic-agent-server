package com.soma.wes.selection.domain

import com.soma.wes.global.BaseEntity
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime

/**
 * 예비 부부가 최종적으로 고른 사진을 담는 앨범. 갤러리당 하나다.
 */
@Entity
@Table(
    name = "photo_selections",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_photo_selections_gallery_id", columnNames = ["gallery_id"]),
    ],
)
class PhotoSelection(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    // allOpen 플러그인이 엔티티를 open으로 만들어 private setter를 쓸 수 없다.
    // 상태 전이는 아래 메서드로만 한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PhotoSelectionStatus = PhotoSelectionStatus.SELECTING

    /** 제출 시각. 되돌리면 다시 null이 된다 — [status]와 늘 같은 방향을 가리킨다. */
    @Column(name = "submitted_at")
    var submittedAt: ZonedDateTime? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 PhotoSelection 이다")

    val isSubmitted: Boolean
        get() = status == PhotoSelectionStatus.SUBMITTED

    /**
     * 제출된 뒤에는 목록이 바뀌지 않는다.
     */
    fun requireEditable() {
        if (isSubmitted) {
            throw SelectionException(SelectionErrorCode.SELECTION_ALREADY_SUBMITTED)
        }
    }

    /** 이미 담긴 사진을 또 담으려는 요청인지 본다.*/
    fun requireNotSelected(alreadySelected: Set<Long>, photoIds: Collection<Long>) {
        if (photoIds.any { it in alreadySelected }) {
            throw SelectionException(SelectionErrorCode.PHOTO_ALREADY_SELECTED)
        }
    }

    /** 담고 나면 몇 장이 되는지를 받아 계약 장수를 넘기는지 본다.*/
    fun requireWithinMax(maxSelectablePhotoCount: Int?, countAfterAdd: Int) {
        if (maxSelectablePhotoCount != null && countAfterAdd > maxSelectablePhotoCount) {
            throw SelectionException(SelectionErrorCode.MAX_SELECTABLE_PHOTO_COUNT_EXCEEDED)
        }
    }

    /** 부부가 고르기를 끝내고 작가에게 넘긴다.*/
    fun submit(selectedCount: Int, at: ZonedDateTime) {
        requireEditable()
        if (selectedCount == 0) {
            throw SelectionException(SelectionErrorCode.EMPTY_SELECTION)
        }

        status = PhotoSelectionStatus.SUBMITTED
        submittedAt = at
    }

    /** 제출을 되돌려 다시 고를 수 있게 한다. 부를 수 있는 것은 작가뿐이다 — 서비스가 그것을 확인한다. */
    fun withdraw() {
        if (!isSubmitted) {
            throw SelectionException(SelectionErrorCode.SELECTION_NOT_SUBMITTED)
        }

        status = PhotoSelectionStatus.SELECTING
        submittedAt = null
    }
}
