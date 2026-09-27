package com.soma.wes.folder.dto.response

import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.domain.DetailFolder
import com.soma.wes.folder.domain.FolderSource

data class DetailFolderResponse(
    val id: Long,
    val galleryId: Long,
    val conceptFolderId: Long,
    val name: String,
    val sortOrder: Int,
    val createdSource: FolderSource,
    val needsReview: Boolean,
    val photoIds: List<Long>,
) {
    // [REFACTOR-A 2026-09-27] 신규. FolderService·AiFolderService의 private detailResponse(...) 두 벌을 대체한다.
    companion object {
        fun of(detail: DetailFolder, photoIds: List<Long>) = DetailFolderResponse(
            id = detail.requiredId,
            galleryId = detail.galleryId,
            conceptFolderId = detail.conceptFolderId,
            name = detail.name,
            sortOrder = detail.sortOrder,
            createdSource = detail.createdSource,
            needsReview = detail.needsReview,
            photoIds = photoIds,
        )
    }
}

data class ConceptFolderResponse(
    val id: Long,
    val galleryId: Long,
    val name: String,
    val sortOrder: Int,
    val createdSource: FolderSource,
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
