package com.soma.wes.category.dto.response

import com.soma.wes.category.domain.CategoryFolder
import com.soma.wes.category.domain.CutType
import com.soma.wes.category.domain.CategorySource

data class DetailFolderResponse(
    val id: Long,
    val galleryId: Long,
    val conceptFolderId: Long,
    val name: String,
    val sortOrder: Int,
    val createdSource: CategorySource,
    val category: CutType?,
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
        fun of(concept: CategoryFolder, details: List<DetailFolderResponse>) = ConceptFolderResponse(
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
