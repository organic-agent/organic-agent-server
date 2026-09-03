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

@Entity
@Table(
    name = "categorization_jobs",
    indexes = [Index(name = "idx_categorization_jobs_gallery", columnList = "gallery_id, created_at")],
)
class CategorizationJob(
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    val mode: CategorizationMode,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CategorizationStatus = CategorizationStatus.RUNNING

    @Column(name = "started_at", nullable = false)
    var startedAt: ZonedDateTime? = null

    @Column(name = "completed_at")
    var completedAt: ZonedDateTime? = null

    @Column(name = "failure_code", length = 80)
    var failureCode: String? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 카테고리화 작업입니다." }

    fun complete(at: ZonedDateTime) {
        status = CategorizationStatus.SUCCEEDED
        completedAt = at
    }
}

enum class CategorizationMode { INITIAL, INCREMENTAL }
enum class CategorizationStatus { RUNNING, SUCCEEDED, FAILED }
