package com.soma.wes.selection.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 선택 앨범에 담긴 사진 한 장.
 *
 * 사진을 `@ManyToOne`으로 잡지 않고 id만 든다. `open-in-view`가 false라 연관을 걸면 서비스
 * 트랜잭션 밖에서 지연 로딩이 터지고, 앨범을 읽을 때 필요한 것은 사진을 한 번에 가져오는 질의
 * 하나뿐이라 연관이 주는 것이 없다. [com.soma.wes.folder.domain.PhotoFolderItem]과 같은 이유다.
 *
 * 담긴 순서를 저장하지 않는 것도 같다 — 화면 순서는 갤러리에서 정한 `displayOrder`를 따른다.
 */
@Entity
// selection_id 단독 조회는 아래 유니크 제약이 만드는 인덱스가 받는다(선두 컬럼이 selection_id다).
@Table(
    name = "photo_selection_items",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_photo_selection_items_selection_id_photo_id",
            columnNames = ["selection_id", "photo_id"],
        ),
    ],
)
class PhotoSelectionItem(

    @Column(name = "selection_id", nullable = false, updatable = false)
    val selectionId: Long,

    /** 항상 원본 사진이다. 보정본으로 담아도 이 값이 원본을 가리켜야 중복·정원 규칙이 산다. */
    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

    /**
     * 보정본으로 담았으면 그 보정 항목([com.soma.wes.retouch.domain.RetouchPhoto])의 id.
     * 원본으로 담았으면 null이고, 납품 조회는 이 값이 있는 항목을 결과 key로 서명한다.
     */
    @Column(name = "retouch_photo_id", updatable = false)
    val retouchPhotoId: Long? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
