package com.soma.wes.category.dto.response

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
