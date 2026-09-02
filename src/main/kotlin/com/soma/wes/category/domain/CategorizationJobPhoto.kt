package com.soma.wes.category.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

data class CategorizationJobPhotoId(
    val jobId: Long = 0,
    val photoId: Long = 0,
) : Serializable

@Entity
@IdClass(CategorizationJobPhotoId::class)
@Table(name = "categorization_job_photos")
class CategorizationJobPhoto(
    @Id
    @Column(name = "job_id", updatable = false)
    val jobId: Long,

    @Id
    @Column(name = "photo_id", updatable = false)
    val photoId: Long,
)
