package com.soma.wes.category.domain

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
    name = "concept_folders",
    indexes = [Index(name = "idx_concept_folders_gallery", columnList = "gallery_id, sort_order")],
)
class ConceptFolder(
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "created_source", nullable = false, updatable = false, length = 20)
    val createdSource: CategorySource,

    /** AI가 만든 컨셉 세트의 분석 잡. 사용자가 직접 만든 컨셉은 null이다. */
    @Column(name = "analysis_job_id", updatable = false)
    val analysisJobId: Long? = null,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 컨셉폴더입니다." }
}
