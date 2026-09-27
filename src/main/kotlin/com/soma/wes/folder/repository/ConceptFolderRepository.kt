package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.ConceptFolder
import org.springframework.data.jpa.repository.JpaRepository

interface ConceptFolderRepository : JpaRepository<ConceptFolder, Long> {
    fun findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId: Long): List<ConceptFolder>
    fun findAllByGalleryIdAndAnalysisJobIdOrderBySortOrderAscIdAsc(
        galleryId: Long,
        analysisJobId: Long,
    ): List<ConceptFolder>
    fun findFirstByGalleryIdAndAnalysisJobIdIsNotNullOrderByAnalysisJobIdDesc(galleryId: Long): ConceptFolder?
    fun existsByGalleryIdAndAnalysisJobId(galleryId: Long, analysisJobId: Long): Boolean
    fun findByIdAndGalleryId(id: Long, galleryId: Long): ConceptFolder?
    fun countByGalleryId(galleryId: Long): Long
}
