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
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(name = "concept_folder_id", nullable = false, updatable = false)
    val conceptFolderId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "created_source", nullable = false, updatable = false, length = 20)
    val createdSource: FolderSource,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    var cutType: CutType? = null,

    @Column(name = "needs_review", nullable = false)
    var needsReview: Boolean = false,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 세부폴더입니다." }
}
