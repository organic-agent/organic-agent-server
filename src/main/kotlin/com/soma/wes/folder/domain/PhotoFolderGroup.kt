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
import org.hibernate.annotations.SQLRestriction
import java.time.ZonedDateTime

/**
 * 자식폴더들을 품는 부모폴더.
 */
@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "photo_folder_groups",
    indexes = [
        Index(name = "idx_photo_folder_groups_gallery_id", columnList = "gallery_id"),
    ],
)
class PhotoFolderGroup(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Embedded
    var name: FolderName,

) : BaseEntity() {

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 PhotoFolderGroup 이다")

    fun rename(name: String) {
        this.name = FolderName.of(name)
    }

    companion object {
        fun of(galleryId: Long, name: String) = PhotoFolderGroup(
            galleryId = galleryId,
            name = FolderName.of(name),
        )
    }
}
