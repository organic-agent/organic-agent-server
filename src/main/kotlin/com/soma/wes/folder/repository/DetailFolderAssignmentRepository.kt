package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.DetailFolderAssignment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

// [GLOSSARY-1 2026-09-27] PhotoFolderAssignmentRepository → DetailFolderAssignmentRepository (용어집 D9)
interface DetailFolderAssignmentRepository : JpaRepository<DetailFolderAssignment, Long> {
    fun findAllByDetailFolderId(detailFolderId: Long): List<DetailFolderAssignment>
    fun findAllByDetailFolderIdIn(detailFolderIds: Collection<Long>): List<DetailFolderAssignment>
    fun findAllByPhotoIdIn(photoIds: Collection<Long>): List<DetailFolderAssignment>
    fun findAllByGalleryIdAndPhotoIdIn(galleryId: Long, photoIds: Collection<Long>): List<DetailFolderAssignment>
    /** 갤러리에서 이미 폴더에 든 사진 id. 사진 id 수천 개를 IN으로 보내는 대신 gallery_id 한 번으로 읽는다. */
    @Query("SELECT a.photoId FROM DetailFolderAssignment a WHERE a.galleryId = :galleryId")
    fun findAllPhotoIdsByGalleryId(@Param("galleryId") galleryId: Long): List<Long>
    fun deleteAllByDetailFolderId(detailFolderId: Long)
    fun deleteAllByDetailFolderIdIn(detailFolderIds: Collection<Long>)
}
