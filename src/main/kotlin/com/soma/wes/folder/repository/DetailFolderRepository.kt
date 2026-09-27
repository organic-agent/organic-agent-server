package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.DetailFolder
import org.springframework.data.jpa.repository.JpaRepository

interface DetailFolderRepository : JpaRepository<DetailFolder, Long> {
    fun findAllByConceptFolderIdOrderBySortOrderAscIdAsc(conceptFolderId: Long): List<DetailFolder>
    fun findAllByConceptFolderIdIn(conceptFolderIds: Collection<Long>): List<DetailFolder>
    fun findByIdAndConceptFolderId(id: Long, conceptFolderId: Long): DetailFolder?
    fun findByIdAndGalleryId(id: Long, galleryId: Long): DetailFolder?
}
