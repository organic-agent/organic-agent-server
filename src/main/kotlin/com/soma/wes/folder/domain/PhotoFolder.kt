package com.soma.wes.folder.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * 사진을 실제로 담는 자식폴더. 항상 부모([PhotoFolderGroup]) 아래에만 존재한다.
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

    /** 피사체 카테고리 칩. AI 폴더 생성이 과반으로 정하고, 이후에는 사용자가 고친다. null이면 없음. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    var category: FolderCategory? = null
        protected set

    /**
     * AI 배정의 확신이 낮았다는 표식. 판정(CLIP 불일치, 낮은 confidence)은 AI 배치가 하고,
     * 사용자가 확인하고 끄면 서버가 다시 켜지 않는다.
     */
    @Column(name = "needs_review", nullable = false)
    var needsReview: Boolean = false
        protected set

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 PhotoFolder 다")

    fun rename(name: String) {
        this.name = FolderName.of(name)
    }

    fun changeCategory(category: FolderCategory?) {
        this.category = category
    }

    fun markReviewed() {
        this.needsReview = false
    }

    companion object {
        fun of(group: PhotoFolderGroup, name: String) = PhotoFolder(
            groupId = group.requiredId,
            galleryId = group.galleryId,
            name = FolderName.of(name),
        )

        /** AI 세트의 자식 하나. 컨셉 이름과 함께 카테고리·확인 필요 표식을 처음부터 단다. */
        fun aiOf(
            group: PhotoFolderGroup,
            name: String,
            category: FolderCategory?,
            needsReview: Boolean,
        ): PhotoFolder = of(group, name).also {
            it.category = category
            it.needsReview = needsReview
        }
    }
}
