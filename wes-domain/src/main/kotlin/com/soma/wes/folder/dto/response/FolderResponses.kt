package com.soma.wes.folder.dto.response

import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.domain.DetailFolder
import com.soma.wes.folder.domain.FolderSource
import io.swagger.v3.oas.annotations.media.Schema

data class DetailFolderResponse(
    val id: Long,
    val galleryId: Long,
    val conceptFolderId: Long,
    val name: String,
    val sortOrder: Int,
    val createdSource: FolderSource,
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
    @field:Schema(
        description = "이 컨셉을 만든 AI 분석 잡 id. AI 폴더 만들기로 생긴 컨셉에만 있고 사람이 만든 컨셉은 null이다. 이름을 바꿔도 남는다.",
        example = "7",
    )
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

data class MergeDetailFolderResponse(
    /** 되돌릴 때 쓰는 합치기 기록 id. */
    val mergeId: Long,
    /** 합친 뒤의 대상 세부 폴더. */
    val target: DetailFolderResponse,
)

data class UndoDetailFolderMergeResponse(
    /** 되살아난 원본 세부 폴더 — 원래 id · 순서 · 출처, 돌아온 사진. */
    val source: DetailFolderResponse,
    /** 사진이 빠진 대상 세부 폴더. */
    val target: DetailFolderResponse,
)
