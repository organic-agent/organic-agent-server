package com.soma.wes.category.repository

import com.soma.wes.category.domain.ConceptFolder
import com.soma.wes.category.domain.DetailFolder
import com.soma.wes.category.domain.PhotoCategoryAssignment
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): ConceptFolder?
}

interface DetailFolderRepository : JpaRepository<DetailFolder, Long> {
    fun findAllByConceptFolderIdOrderBySortOrderAscIdAsc(conceptFolderId: Long): List<DetailFolder>
    fun findAllByConceptFolderIdIn(conceptFolderIds: Collection<Long>): List<DetailFolder>
    fun findByIdAndConceptFolderId(id: Long, conceptFolderId: Long): DetailFolder?
    fun findByIdAndGalleryId(id: Long, galleryId: Long): DetailFolder?
}

interface PhotoCategoryAssignmentRepository : JpaRepository<PhotoCategoryAssignment, Long> {
    fun findAllByDetailFolderId(detailFolderId: Long): List<PhotoCategoryAssignment>
    fun findAllByDetailFolderIdIn(detailFolderIds: Collection<Long>): List<PhotoCategoryAssignment>
    fun findAllByPhotoIdIn(photoIds: Collection<Long>): List<PhotoCategoryAssignment>
    fun findAllByGalleryIdAndPhotoIdIn(galleryId: Long, photoIds: Collection<Long>): List<PhotoCategoryAssignment>
    /** 갤러리에서 이미 폴더에 든 사진 id. 사진 id 수천 개를 IN으로 보내는 대신 gallery_id 한 번으로 읽는다. */
    @Query("SELECT a.photoId FROM PhotoCategoryAssignment a WHERE a.galleryId = :galleryId")
    fun findAllPhotoIdsByGalleryId(@Param("galleryId") galleryId: Long): List<Long>
    fun deleteAllByDetailFolderId(detailFolderId: Long)
    fun deleteAllByDetailFolderIdIn(detailFolderIds: Collection<Long>)
}
