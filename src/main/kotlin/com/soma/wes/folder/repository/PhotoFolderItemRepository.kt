package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.PhotoFolderItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PhotoFolderItemRepository : JpaRepository<PhotoFolderItem, Long> {

    fun findAllByFolderId(folderId: Long): List<PhotoFolderItem>

    fun findAllByFolderIdIn(folderIds: Collection<Long>): List<PhotoFolderItem>

    fun countByFolderId(folderId: Long): Long

    fun deleteByFolderIdAndPhotoId(folderId: Long, photoId: Long): Long

    /**
     * 폴더를 지울 때 함께 지운다. 외래키를 걸지 않았으므로 애플리케이션이 책임진다.
     *
     * 파생 삭제(`deleteAllByFolderId`)로 두면 Spring Data가 항목을 전부 조회한 뒤 한 건씩
     * 지운다. 폴더 하나가 수백 장일 수 있어 벌크 삭제로 못박는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PhotoFolderItem i WHERE i.folderId = :folderId")
    fun deleteAllByFolderId(@Param("folderId") folderId: Long): Int
}
