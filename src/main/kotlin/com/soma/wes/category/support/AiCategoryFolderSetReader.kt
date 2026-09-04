package com.soma.wes.category.support

import com.soma.wes.category.dto.FolderSetDetailDto
import com.soma.wes.category.repository.ConceptFolderRepository
import com.soma.wes.category.repository.DetailFolderRepository
import com.soma.wes.category.repository.PhotoCategoryAssignmentRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 추천 도메인이 카테고리를 읽는 경계다 — 세트 키와, 사진이 든 폴더의 표시 이름만 노출한다. */
@Component
class AiCategoryFolderSetReader(
    private val conceptFolderRepository: ConceptFolderRepository,
    private val detailFolderRepository: DetailFolderRepository,
    private val assignmentRepository: PhotoCategoryAssignmentRepository,
) {
    @Transactional(readOnly = true)
    fun latestSetJobId(galleryId: Long): Long? = conceptFolderRepository
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

    /** photo_id → "컨셉 › 세부" 표시 이름. 어느 세부 폴더에도 없는 사진은 빠진다(호출자가 미분류로 본다). */
    @Transactional(readOnly = true)
    fun folderNamesByPhotoId(photoIds: Collection<Long>): Map<Long, String> {
        val assignments = assignmentRepository.findAllByPhotoIdIn(photoIds)
        if (assignments.isEmpty()) return emptyMap()
        val details = detailFolderRepository.findAllById(assignments.map { it.detailFolderId }).associateBy { it.requiredId }
        val concepts = conceptFolderRepository.findAllById(details.values.map { it.conceptFolderId }).associateBy { it.requiredId }
        return assignments.mapNotNull { assignment ->
            val detail = details[assignment.detailFolderId] ?: return@mapNotNull null
            val concept = concepts[detail.conceptFolderId] ?: return@mapNotNull null
            assignment.photoId to "${concept.name} › ${detail.name}"
        }.toMap()
    }
}
