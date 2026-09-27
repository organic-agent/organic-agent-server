package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.ConceptFolder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ConceptFolderRepository : JpaRepository<ConceptFolder, Long> {
    fun findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId: Long): List<ConceptFolder>
    fun findFirstByGalleryIdAndAnalysisJobIdIsNotNullOrderByAnalysisJobIdDesc(galleryId: Long): ConceptFolder?
    fun existsByGalleryIdAndAnalysisJobId(galleryId: Long, analysisJobId: Long): Boolean
    fun findByIdAndGalleryId(id: Long, galleryId: Long): ConceptFolder?
    fun countByGalleryId(galleryId: Long): Long
    fun findAllByGalleryIdAndAnalysisJobIdOrderBySortOrderAscIdAsc(
        galleryId: Long,
        analysisJobId: Long,
    ): List<ConceptFolder>

    /**
     * 새 컨셉 폴더가 받을 정렬 순서 — 기존 폴더 맨 뒤(가장 큰 sortOrder + 1), 폴더가 없으면 0.
     * 개수로 정하지 않는다: 폴더를 지워도 남은 폴더의 sortOrder는 당겨지지 않아, 개수를 쓰면 새 폴더가 기존 폴더 사이에 끼어든다.
     */
    @Query("SELECT COALESCE(MAX(c.sortOrder) + 1, 0) FROM ConceptFolder c WHERE c.galleryId = :galleryId")
    fun findNextSortOrderByGalleryId(@Param("galleryId") galleryId: Long): Int
}
