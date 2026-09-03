package com.soma.wes.category.support

import com.soma.wes.category.repository.ConceptFolderRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 추천 도메인이 현재 AI 카테고리 세트 키만 읽도록 제한한 경계다. */
@Component
class AiCategoryFolderSetReader(
    private val conceptFolderRepository: ConceptFolderRepository,
) {
    @Transactional(readOnly = true)
    fun latestSetJobId(galleryId: Long): Long? = conceptFolderRepository
        .findFirstByGalleryIdAndAnalysisJobIdIsNotNullOrderByAnalysisJobIdDesc(galleryId)
        ?.analysisJobId

    @Transactional(readOnly = true)
    fun setExists(galleryId: Long, analysisJobId: Long): Boolean =
        conceptFolderRepository.existsByGalleryIdAndAnalysisJobId(galleryId, analysisJobId)
}
