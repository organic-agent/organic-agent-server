package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.PhotoFolderAssignment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PhotoFolderAssignmentRepository : JpaRepository<PhotoFolderAssignment, Long> {
    fun findAllByDetailFolderId(detailFolderId: Long): List<PhotoFolderAssignment>
    fun findAllByDetailFolderIdIn(detailFolderIds: Collection<Long>): List<PhotoFolderAssignment>
    fun findAllByPhotoIdIn(photoIds: Collection<Long>): List<PhotoFolderAssignment>
    fun findAllByGalleryIdAndPhotoIdIn(galleryId: Long, photoIds: Collection<Long>): List<PhotoFolderAssignment>
    /** 갤러리에서 이미 폴더에 든 사진 id. 사진 id 수천 개를 IN으로 보내는 대신 gallery_id 한 번으로 읽는다. */
    @Query("SELECT a.photoId FROM PhotoFolderAssignment a WHERE a.galleryId = :galleryId")
    fun findAllPhotoIdsByGalleryId(@Param("galleryId") galleryId: Long): List<Long>
    fun deleteAllByDetailFolderId(detailFolderId: Long)
    fun deleteAllByDetailFolderIdIn(detailFolderIds: Collection<Long>)
}
