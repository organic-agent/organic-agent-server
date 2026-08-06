package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.PhotoFolderItem
import org.springframework.data.jpa.repository.JpaRepository

interface PhotoFolderItemRepository : JpaRepository<PhotoFolderItem, Long> {

    fun findAllByFolderId(folderId: Long): List<PhotoFolderItem>

    fun findAllByFolderIdIn(folderIds: Collection<Long>): List<PhotoFolderItem>

    fun countByFolderId(folderId: Long): Long

    fun deleteByFolderIdAndPhotoId(folderId: Long, photoId: Long): Long

    /** 폴더를 지울 때 함께 지운다. 외래키를 걸지 않았으므로 애플리케이션이 책임진다. */
    fun deleteAllByFolderId(folderId: Long)
}
