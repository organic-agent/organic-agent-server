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
 * 예비 부부가 이름을 붙여 확정한 사진 묶음.
 *
 * 클러스터에서 출발하지만 클러스터를 가리키지는 않는다. 임계값이 바뀌면 클러스터는 다른 모양이
 * 되고 사진이 더 올라와도 달라지는데, 폴더는 그때마다 흔들리면 안 된다. 그래서 만든 시점의
 * 사진 목록을 [PhotoFolderItem] 행으로 고정한다 — 임계값도, 클러스터 식별자도 남기지 않는다.
 */
@Entity
@Table(
    name = "photo_folders",
    indexes = [
        Index(name = "idx_photo_folders_gallery_id", columnList = "gallery_id"),
    ],
)
class PhotoFolder(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    var name: String,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 PhotoFolder 다")

    fun rename(name: String) {
        this.name = normalizeName(name)
    }

    companion object {
        const val MAX_NAME_LENGTH = 100

        /**
         * 앞뒤 공백을 떼고 길이를 확인한다.
         *
         * 컨트롤러의 `@Valid`에만 맡기지 않는다 — 그 검증은 컨트롤러를 거칠 때만 돌고,
         * 서비스를 다른 곳에서 부르면 통째로 건너뛴다. 이름은 화면에 그대로 나가는 값이라
         * 공백만으로 된 이름이 들어오면 사용자에게는 이름 없는 폴더로 보인다.
         */
        fun normalizeName(name: String): String {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "폴더 이름은 비어 있을 수 없습니다." }
            require(trimmed.length <= MAX_NAME_LENGTH) { "폴더 이름은 ${MAX_NAME_LENGTH}자를 넘을 수 없습니다." }
            return trimmed
        }

        fun of(galleryId: Long, name: String) = PhotoFolder(
            galleryId = galleryId,
            name = normalizeName(name),
        )
    }
}
