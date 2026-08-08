package com.soma.wes.selection.repository

import com.soma.wes.selection.domain.PhotoSelectionItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PhotoSelectionItemRepository : JpaRepository<PhotoSelectionItem, Long> {

    fun findAllBySelectionId(selectionId: Long): List<PhotoSelectionItem>

    fun countBySelectionId(selectionId: Long): Long

    fun deleteBySelectionIdAndPhotoId(selectionId: Long, photoId: Long): Long

    /**
     * 여러 장을 한 번에 뺀다.
     *
     * 파생 삭제로 두면 Spring Data가 항목을 전부 조회한 뒤 한 건씩 지운다. 계약 장수만큼
     * 담긴 앨범을 한 번에 비우는 요청이 그대로 그만큼의 DELETE가 된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PhotoSelectionItem i WHERE i.selectionId = :selectionId AND i.photoId IN :photoIds")
    fun deleteAllBySelectionIdAndPhotoIdIn(
        @Param("selectionId") selectionId: Long,
        @Param("photoIds") photoIds: Collection<Long>,
    ): Int
}
