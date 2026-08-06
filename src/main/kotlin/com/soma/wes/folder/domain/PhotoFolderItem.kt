package com.soma.wes.folder.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 폴더에 담긴 사진 한 장.
 *
 * 사진을 `@ManyToOne`으로 잡지 않고 id만 든다. `open-in-view`가 false라 연관을 걸면 서비스
 * 트랜잭션 밖에서 지연 로딩이 터지고, 폴더를 읽을 때 필요한 것은 사진 목록을 한 번에 가져오는
 * 질의 하나뿐이라 연관이 주는 것이 없다.
 *
 * 담긴 순서를 따로 저장하지 않는다. 화면 순서는 갤러리에서 정한 `displayOrder`를 그대로 따르는
 * 것이 자연스럽고, 폴더마다 순서를 따로 두면 원본 정렬을 바꿨을 때 둘이 어긋난다.
 */
@Entity
// folder_id 단독 조회는 아래 유니크 제약이 만드는 인덱스가 받는다(선두 컬럼이 folder_id다).
// 따로 인덱스를 더 두면 쓰기 비용만 늘어난다.
@Table(
    name = "photo_folder_items",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_photo_folder_items_folder_id_photo_id",
            columnNames = ["folder_id", "photo_id"],
        ),
    ],
)
class PhotoFolderItem(

    @Column(name = "folder_id", nullable = false, updatable = false)
    val folderId: Long,

    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
