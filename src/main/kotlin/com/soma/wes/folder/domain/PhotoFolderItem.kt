package com.soma.wes.folder.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 자식폴더에 담긴 사진 한 장.
 *
 * groupId는 folderId로도 알 수 있는 값이지만 행에 함께 든다 — "같은 부모 아래 사진 중복
 * 금지"의 단위가 부모라서, 그 유니크 제약을 DB가 지키려면 부모 id가 이 행에 있어야 한다.
 * 자식폴더는 부모를 옮겨 다니지 않으므로 두 값이 어긋날 일은 없다.
 */
@Entity
@Table(
    name = "photo_folder_items",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_photo_folder_items_group_id_photo_id",
            columnNames = ["group_id", "photo_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_photo_folder_items_folder_id", columnList = "folder_id"),
    ],
)
class PhotoFolderItem(

    @Column(name = "group_id", nullable = false, updatable = false)
    val groupId: Long,

    /** 같은 부모의 다른 자식으로 사진을 옮길 때 이 값만 바뀐다. 벌크 UPDATE로 처리한다. */
    @Column(name = "folder_id", nullable = false)
    val folderId: Long,

    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

    /** 앨범 목업과 일반 폴더의 명시 순서. 새 사진은 현재 폴더의 마지막 순서 뒤에 붙는다. */
    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,

    /** 프레임 안에서 사용할 정규화 크롭 영역(x/y/width/height 등). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "crop_json", columnDefinition = "jsonb")
    val crop: Map<String, Any?>? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
