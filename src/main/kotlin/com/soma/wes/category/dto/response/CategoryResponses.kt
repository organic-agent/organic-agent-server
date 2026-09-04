package com.soma.wes.category.dto.response

import com.soma.wes.category.domain.CategorizationJob
import com.soma.wes.category.domain.CategorizationMode
import com.soma.wes.category.domain.CategorizationStatus
import com.soma.wes.category.domain.CategorizationPhotoStatus
import com.soma.wes.category.domain.CategorizationJobPhoto
import com.soma.wes.category.domain.CategorySource
import com.soma.wes.category.domain.ConceptFolder
import com.soma.wes.category.domain.DetailFolder
import com.soma.wes.category.domain.DetailFolderCategory

data class DetailFolderResponse(
    val id: Long,
    val galleryId: Long,
    val conceptFolderId: Long,
    val name: String,
    val sortOrder: Int,
    val createdSource: CategorySource,
    val category: DetailFolderCategory?,
    val needsReview: Boolean,
    val photoIds: List<Long>,
)

data class ConceptFolderResponse(
    val id: Long,
    val galleryId: Long,
    val name: String,
    val sortOrder: Int,
    val createdSource: CategorySource,
    val analysisJobId: Long?,
    val details: List<DetailFolderResponse>,
) {
    companion object {
        fun of(concept: ConceptFolder, details: List<DetailFolderResponse>) = ConceptFolderResponse(
            id = concept.requiredId,
            galleryId = concept.galleryId,
            name = concept.name,
            sortOrder = concept.sortOrder,
            createdSource = concept.createdSource,
            analysisJobId = concept.analysisJobId,
            details = details,
        )
    }
}

data class CategorizationJobResponse(
    val id: Long,
    val galleryId: Long,
    val mode: CategorizationMode,
    val status: CategorizationStatus,
    val processedPhotoCount: Int,
    val photos: List<CategorizationJobPhotoResponse>,
) {
    companion object {
        fun of(job: CategorizationJob, photos: List<CategorizationJobPhoto> = emptyList()) = CategorizationJobResponse(
            id = job.requiredId,
            galleryId = job.galleryId,
            mode = job.mode,
            status = job.status,
            processedPhotoCount = photos.count { it.status != CategorizationPhotoStatus.PENDING },
            photos = photos.map(CategorizationJobPhotoResponse::from),
        )
    }
}

data class CategorizationJobPhotoResponse(
    val photoId: Long,
    val status: CategorizationPhotoStatus,
    val failureCode: String?,
    val processedAt: java.time.ZonedDateTime?,
) {
    companion object {
        fun from(row: CategorizationJobPhoto) = CategorizationJobPhotoResponse(
            photoId = row.photoId,
            status = row.status,
            failureCode = row.failureCode,
            processedAt = row.processedAt,
        )
    }
}
