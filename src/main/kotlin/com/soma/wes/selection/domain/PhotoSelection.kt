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
 *
 * [com.soma.wes.folder.domain.PhotoFolder]와 다른 것이다. 폴더는 비슷한 사진을 묶어 이름 붙이는
 * 중간 정리 도구라 여러 개를 만들었다 지웠다 하지만, 이쪽은 작가에게 넘길 최종 목록이라 하나뿐이고
 * 제출되면 잠긴다. 둘은 이어져 있지 않다 — 폴더에서 고른 사진도 id로 하나씩 담는다.
 *
 * **계약 장수를 여기에 두지 않는다.** 그 값은 계약에서 나오지 고르는 과정에서 나오지 않아
 * [com.soma.wes.gallery.domain.Gallery]가 들고 있고, 앨범은 넣고 뺄 때마다 그것을 읽는다.
 * 사본을 뜨면 작가가 장수를 고친 뒤에도 앨범은 옛 값으로 막거나 열어준다.
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
     *
     * 작가는 제출된 목록을 보고 보정에 들어간다. 그 뒤에 조용히 한 장이 바뀌면 어느 쪽이
     * 최종인지 알 수 있는 사람이 아무도 없어진다 — 부부는 바꿨다고 생각하고, 작가는 받은
     * 목록대로 작업한다.
     */
    fun requireEditable() {
        if (isSubmitted) {
            throw SelectionException(SelectionErrorCode.SELECTION_ALREADY_SUBMITTED)
        }
    }

    /**
     * 이미 담긴 사진을 또 담으려는 요청인지 본다.
     *
     * 겹치는 것만 빼고 담지 않는다. 부부 둘이 각자의 화면에서 고르는 물건이라, 겹쳤다는 것은
     * 요청을 보낸 쪽의 화면이 낡았다는 뜻이다. 일부만 담아두면 화면과 실제가 더 벌어지고,
     * 사용자는 자기가 담은 것과 상대가 담아둔 것을 구분할 수 없게 된다.
     *
     * DB의 유니크 제약이 마지막으로 막지만 거기까지 가면 500이다. 여기서 이유 있는 409로 돌린다.
     */
    fun requireNotSelected(alreadySelected: Set<Long>, photoIds: Collection<Long>) {
        if (photoIds.any { it in alreadySelected }) {
            throw SelectionException(SelectionErrorCode.PHOTO_ALREADY_SELECTED)
        }
    }

    /**
     * 담고 나면 몇 장이 되는지를 받아 계약 장수를 넘기는지 본다.
     *
     * 넣을 사진 목록이 아니라 **결과 장수**를 받는 이유는, 통째로 막기 위해서다. 한 장씩
     * 검사하면 들어갈 수 있는 만큼 들어가고 나머지가 조용히 버려진다.
     *
     * `maxSelectablePhotoCount`는 갤러리에서 온다. null이면 제한이 없다.
     */
    fun requireWithinMax(maxSelectablePhotoCount: Int?, countAfterAdd: Int) {
        if (maxSelectablePhotoCount != null && countAfterAdd > maxSelectablePhotoCount) {
            throw SelectionException(SelectionErrorCode.MAX_SELECTABLE_PHOTO_COUNT_EXCEEDED)
        }
    }

    /**
     * 부부가 고르기를 끝내고 작가에게 넘긴다.
     *
     * 계약 장수에 못 미쳐도 받는다. 50장 계약에 45장만 고르는 일은 실제로 있고, 그때 필요한
     * 것은 제출을 막는 것이 아니라 작가가 몇 장을 받았는지 아는 것이다 — 응답이 목표와 현재
     * 장수를 함께 주므로 화면이 "5장 덜 골랐습니다"까지 물어보고 보낼 수 있다.
     *
     * 반대로 0장은 막는다. 그것은 덜 고른 것이 아니라 고르지 않은 것이고, 작가가 받을 것이 없다.
     */
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
