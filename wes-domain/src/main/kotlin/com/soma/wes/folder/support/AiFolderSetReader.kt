package com.soma.wes.folder.support

import com.soma.wes.folder.dto.FolderSetDetailDto
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 추천 도메인이 폴더를 읽는 경계다 — 세트 키, 세부 폴더와 그 사진, 사진이 든 세부 폴더 id만 노출한다. */
@Component
class AiFolderSetReader(
    private val conceptFolderRepository: ConceptFolderRepository,
    private val detailFolderRepository: DetailFolderRepository,
    private val assignmentRepository: DetailFolderAssignmentRepository,
) {
    @Transactional(readOnly = true)
    // [GLOSSARY-1 2026-09-27] latestSetJobId → latestAnalysisJobId (용어집: 폴더 세트의 키는 analysis_job_id)
    fun latestAnalysisJobId(galleryId: Long): Long? = conceptFolderRepository
        .findFirstByGalleryIdAndAnalysisJobIdIsNotNullOrderByAnalysisJobIdDesc(galleryId)
        ?.analysisJobId

    @Transactional(readOnly = true)
    fun setExists(galleryId: Long, analysisJobId: Long): Boolean =
        conceptFolderRepository.existsByGalleryIdAndAnalysisJobId(galleryId, analysisJobId)

    /**
     * 세트([analysisJobId])의 세부 폴더와 현재 배정. 사용자가 사진을 옮기거나 폴더를 지웠으면 그 상태가 반영된다 —
     * 추천은 "지금 이 폴더에 든 사진" 위에서 돈다. 빈 세부 폴더도 돌려준다(추천 0장).
     */
    @Transactional(readOnly = true)
    fun setFolders(galleryId: Long, analysisJobId: Long): List<FolderSetDetailDto> {
        val concepts = conceptFolderRepository
            .findAllByGalleryIdAndAnalysisJobIdOrderBySortOrderAscIdAsc(galleryId, analysisJobId)
        if (concepts.isEmpty()) return emptyList()
        val conceptById = concepts.associateBy { it.requiredId }
        val details = detailFolderRepository.findAllByConceptFolderIdIn(conceptById.keys)
            .sortedWith(compareBy({ it.conceptFolderId }, { it.sortOrder }, { it.requiredId }))
        val photoIdsByDetail = assignmentRepository.findAllByDetailFolderIdIn(details.map { it.requiredId })
            .groupBy({ it.detailFolderId }, { it.photoId })
        return details.map { detail ->
            FolderSetDetailDto(
                detailFolderId = detail.requiredId,
                conceptName = conceptById.getValue(detail.conceptFolderId).name,
                detailName = detail.name,
                photoIds = photoIdsByDetail[detail.requiredId].orEmpty(),
            )
        }
    }

    /** 세부폴더 하나와 지금 든 사진. 세트와 무관하게(사용자가 만든 폴더도) 읽는다 — 폴더 범위 추천의 대상이다. */
    @Transactional(readOnly = true)
    fun detailFolder(galleryId: Long, detailFolderId: Long): FolderSetDetailDto? {
        val detail = detailFolderRepository.findByIdAndGalleryId(detailFolderId, galleryId) ?: return null
        val concept = conceptFolderRepository.findById(detail.conceptFolderId).orElse(null) ?: return null
        return FolderSetDetailDto(
            detailFolderId = detail.requiredId,
            conceptName = concept.name,
            detailName = detail.name,
            photoIds = assignmentRepository.findAllByDetailFolderId(detail.requiredId).map { it.photoId },
        )
    }

    /** photo_id → 지금 든 세부폴더 id. 어느 폴더에도 없는 사진은 빠진다. 추천 응답이 "현재 폴더"를 붙이는 근거다. */
    @Transactional(readOnly = true)
    fun detailIdsByPhotoId(photoIds: Collection<Long>): Map<Long, Long> =
        if (photoIds.isEmpty()) emptyMap()
        else assignmentRepository.findAllByPhotoIdIn(photoIds).associate { it.photoId to it.detailFolderId }
}
