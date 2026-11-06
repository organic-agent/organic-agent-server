package com.soma.wes.folder.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.ZonedDateTime
import org.hibernate.annotations.SQLRestriction

@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "detail_folders",
    indexes = [Index(name = "idx_detail_folders_concept", columnList = "concept_folder_id, sort_order")],
)
class DetailFolder(
    /**
     * [conceptFolderId]로도 알 수 있는 값이지만 행에 함께 둔다(V5) — 복합 FK(`gallery_id, concept_folder_id` → `concept_folders`)가
     * 다른 갤러리의 컨셉 아래에 세부 폴더가 생기지 않게 하고, `uk_detail_folders_gallery_id_id`가 배정 행의 복합 FK 대상이 된다.
     * 경로의 갤러리로 세부 폴더를 찾는 조회(`findByIdAndGalleryId`)도 컨셉을 거치지 않는다.
     */
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(name = "concept_folder_id", nullable = false, updatable = false)
    val conceptFolderId: Long,

    name: String,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "created_source", nullable = false, updatable = false, length = 20)
    val createdSource: FolderSource,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "deleted_at", nullable = true)
    var deletedAt: ZonedDateTime? = null
        protected set

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 세부폴더입니다." }

    @Column(name = "name", nullable = false, length = FolderName.MAX_LENGTH)
    var name: String = name
        protected set

    /** 이름만 바꾼다. 사진 배정과 출처는 그대로다. */
    fun rename(name: String) {
        this.name = FolderName.requireValid(name)
    }

    /** 다른 폴더에 합쳐져 숨는다. 행이 남아 있어야 되돌릴 때 id · 순서 · 출처가 그대로 돌아온다([DetailFolderMerge]). */
    fun hide(at: ZonedDateTime) {
        deletedAt = at
    }

    fun unhide() {
        deletedAt = null
    }
}
