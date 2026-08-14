package com.soma.wes.folder.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * 자식폴더들을 품는 부모폴더.
 */
@Entity
@Table(
    name = "photo_folder_groups",
    indexes = [
        Index(name = "idx_photo_folder_groups_gallery_id", columnList = "gallery_id"),
    ],
)
class PhotoFolderGroup(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(nullable = false, length = FolderName.MAX_LENGTH)
    var name: String,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 PhotoFolderGroup 이다")

    fun rename(name: String) {
        this.name = FolderName.normalize(name)
    }

    companion object {
        fun of(galleryId: Long, name: String) = PhotoFolderGroup(
            galleryId = galleryId,
            name = FolderName.normalize(name),
        )
    }
}
