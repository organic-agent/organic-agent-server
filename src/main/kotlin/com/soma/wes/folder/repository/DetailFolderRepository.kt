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
     * 다른 폴더에 합쳐져 숨은 세부 폴더. `@SQLRestriction`이 숨은 행을 거르므로 native로 읽는다.
     * 합치기를 되돌릴 때만 쓴다 — 숨지 않은 폴더는 [findByIdAndGalleryId]로 찾는다.
     */
    @Query(
        value = "SELECT * FROM detail_folders WHERE id = :id AND gallery_id = :galleryId AND deleted_at IS NOT NULL",
        nativeQuery = true,
    )
    fun findHiddenByIdAndGalleryId(@Param("id") id: Long, @Param("galleryId") galleryId: Long): DetailFolder?

    /**
     * 컨셉 폴더 안에 새 세부 폴더가 받을 정렬 순서 — 기존 세부 폴더 맨 뒤(가장 큰 sortOrder + 1), 없으면 0.
     * 개수로 정하지 않는 이유는 [ConceptFolderRepository.findNextSortOrderByGalleryId]와 같다.
     * 숨은 폴더(합쳐짐 · 관리자 휴지통)도 센다 — 합치기를 되돌려 돌아온 폴더가 그 사이 생긴 폴더와 같은 순서를 갖지 않게 한다.
     */
    @Query(
        value = "SELECT COALESCE(MAX(sort_order) + 1, 0) FROM detail_folders WHERE concept_folder_id = :conceptFolderId",
        nativeQuery = true,
    )
    fun findNextSortOrderByConceptFolderId(@Param("conceptFolderId") conceptFolderId: Long): Int
}
