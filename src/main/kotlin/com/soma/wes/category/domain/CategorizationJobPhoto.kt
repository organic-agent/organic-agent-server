package com.soma.wes.category.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.ZonedDateTime

data class CategorizationJobPhotoId(
    val jobId: Long = 0,
    val photoId: Long = 0,
) : Serializable

@Entity
@IdClass(CategorizationJobPhotoId::class)
@Table(name = "categorization_job_photos")
class CategorizationJobPhoto(
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Id
    @Column(name = "job_id", updatable = false)
    val jobId: Long,

    @Id
    @Column(name = "photo_id", updatable = false)
    val photoId: Long,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CategorizationPhotoStatus = CategorizationPhotoStatus.PENDING

    @Column(name = "failure_code", length = 80)
    var failureCode: String? = null

    @Column(name = "processed_at")
    var processedAt: ZonedDateTime? = null

    fun assigned(at: ZonedDateTime) {
        status = CategorizationPhotoStatus.ASSIGNED
        processedAt = at
        failureCode = null
    }

    fun unclassified(at: ZonedDateTime) {
        status = CategorizationPhotoStatus.UNCLASSIFIED
        processedAt = at
        failureCode = null
    }

    fun failed(code: String, at: ZonedDateTime) {
        status = CategorizationPhotoStatus.FAILED
        failureCode = code
        processedAt = at
    }
}

enum class CategorizationPhotoStatus { PENDING, ASSIGNED, UNCLASSIFIED, FAILED }
