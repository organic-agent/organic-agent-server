package com.soma.wes.folder.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * 사진을 실제로 담는 자식폴더. 항상 부모([PhotoFolderGroup]) 아래에만 존재한다.
 *
 * galleryId는 group으로도 알 수 있는 값이지만 함께 든다 — 인가가 갤러리 단위라, 협업 세션
 * 시드처럼 갤러리와 폴더만 아는 호출자가 부모를 거치지 않고 (id, galleryId)로 폴더를 확인한다.
 */
@Entity
@Table(
    name = "photo_folders",
    indexes = [
        Index(name = "idx_photo_folders_group_id", columnList = "group_id"),
        Index(name = "idx_photo_folders_gallery_id", columnList = "gallery_id"),
    ],
)
class PhotoFolder(

    @Column(name = "group_id", nullable = false, updatable = false)
    val groupId: Long,

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Embedded
    var name: FolderName,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 PhotoFolder 다")

    fun rename(name: String) {
        this.name = FolderName.of(name)
    }

    companion object {
        fun of(group: PhotoFolderGroup, name: String) = PhotoFolder(
            groupId = group.requiredId,
            galleryId = group.galleryId,
            name = FolderName.of(name),
        )
    }
}
