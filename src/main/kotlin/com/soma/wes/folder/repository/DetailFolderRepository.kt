package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.DetailFolder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DetailFolderRepository : JpaRepository<DetailFolder, Long> {
    fun findAllByConceptFolderIdOrderBySortOrderAscIdAsc(conceptFolderId: Long): List<DetailFolder>
    fun findAllByConceptFolderIdIn(conceptFolderIds: Collection<Long>): List<DetailFolder>
    fun findByIdAndConceptFolderId(id: Long, conceptFolderId: Long): DetailFolder?
    fun findByIdAndGalleryId(id: Long, galleryId: Long): DetailFolder?

    /**
     * 컨셉 폴더 안에 새 세부 폴더가 받을 정렬 순서 — 기존 세부 폴더 맨 뒤(가장 큰 sortOrder + 1), 없으면 0.
     * 개수로 정하지 않는 이유는 [ConceptFolderRepository.findNextSortOrderByGalleryId]와 같다.
     */
    @Query("SELECT COALESCE(MAX(d.sortOrder) + 1, 0) FROM DetailFolder d WHERE d.conceptFolderId = :conceptFolderId")
    fun findNextSortOrderByConceptFolderId(@Param("conceptFolderId") conceptFolderId: Long): Int
}
