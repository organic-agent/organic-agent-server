package com.soma.wes.category.dto.response

import com.soma.wes.category.domain.CategorizationJob
import com.soma.wes.category.domain.CategorizationMode
import com.soma.wes.category.domain.CategorizationStatus
import com.soma.wes.category.domain.CategorySource
import com.soma.wes.category.domain.ConceptFolder
import com.soma.wes.category.domain.DetailFolder

data class DetailFolderResponse(
    val id: Long,
    val conceptFolderId: Long,
    val name: String,
    val sortOrder: Int,
    val createdSource: CategorySource,
    val photoIds: List<Long>,
)

data class ConceptFolderResponse(
    val id: Long,
    val galleryId: Long,
    val name: String,
    val sortOrder: Int,
    val createdSource: CategorySource,
    val details: List<DetailFolderResponse>,
) {
    companion object {
        fun of(concept: ConceptFolder, details: List<DetailFolderResponse>) = ConceptFolderResponse(
            id = concept.requiredId,
            galleryId = concept.galleryId,
            name = concept.name,
            sortOrder = concept.sortOrder,
            createdSource = concept.createdSource,
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
) {
    companion object {
        fun of(job: CategorizationJob, processedPhotoCount: Int) = CategorizationJobResponse(
            id = job.requiredId,
            galleryId = job.galleryId,
            mode = job.mode,
            status = job.status,
            processedPhotoCount = processedPhotoCount,
        )
    }
}
