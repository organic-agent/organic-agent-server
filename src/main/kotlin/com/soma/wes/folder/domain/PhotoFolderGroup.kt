package com.soma.wes.folder.domain

import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
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
 *
 * 이름과 id만 가지고 사진을 직접 담지 않는다 — 사진은 항상 자식([PhotoFolder])에 담긴다.
 * "같은 부모 아래 자식들 간에는 사진이 중복될 수 없다"는 정책의 단위가 이 엔티티라서,
 * 그 사진 구성을 바꾸는 쓰기는 모두 이 행을 잠그고 시작한다.
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

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    var name: String,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 PhotoFolderGroup 이다")

    fun rename(name: String) {
        this.name = normalizeName(name)
    }

    companion object {
        const val MAX_NAME_LENGTH = 100

        fun of(galleryId: Long, name: String) = PhotoFolderGroup(
            galleryId = galleryId,
            name = normalizeName(name),
        )

        fun normalizeName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) {
                throw FolderException(FolderErrorCode.INVALID_FOLDER_NAME)
            }
            return trimmed
        }
    }
}
