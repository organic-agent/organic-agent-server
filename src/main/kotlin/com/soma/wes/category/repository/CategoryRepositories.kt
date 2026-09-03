package com.soma.wes.category.repository

import com.soma.wes.category.domain.CategorizationJob
import com.soma.wes.category.domain.CategorizationJobPhoto
import com.soma.wes.category.domain.CategorizationJobPhotoId
import com.soma.wes.category.domain.CategorizationMode
import com.soma.wes.category.domain.CategorizationStatus
import com.soma.wes.category.domain.ConceptFolder
import com.soma.wes.category.domain.DetailFolder
import com.soma.wes.category.domain.PhotoCategoryAssignment
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface ConceptFolderRepository : JpaRepository<ConceptFolder, Long> {
    fun findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId: Long): List<ConceptFolder>
    fun findAllByGalleryIdAndAnalysisJobIdOrderBySortOrderAscIdAsc(
        galleryId: Long,
        analysisJobId: Long,
    ): List<ConceptFolder>
    fun findFirstByGalleryIdAndAnalysisJobIdIsNotNullOrderByAnalysisJobIdDesc(galleryId: Long): ConceptFolder?
    fun existsByGalleryIdAndAnalysisJobId(galleryId: Long, analysisJobId: Long): Boolean
    fun findByIdAndGalleryId(id: Long, galleryId: Long): ConceptFolder?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): ConceptFolder?
}

interface DetailFolderRepository : JpaRepository<DetailFolder, Long> {
    fun findAllByConceptFolderIdOrderBySortOrderAscIdAsc(conceptFolderId: Long): List<DetailFolder>
    fun findAllByConceptFolderIdIn(conceptFolderIds: Collection<Long>): List<DetailFolder>
    fun findByIdAndConceptFolderId(id: Long, conceptFolderId: Long): DetailFolder?
}

interface PhotoCategoryAssignmentRepository : JpaRepository<PhotoCategoryAssignment, Long> {
    fun findAllByDetailFolderId(detailFolderId: Long): List<PhotoCategoryAssignment>
    fun findAllByDetailFolderIdIn(detailFolderIds: Collection<Long>): List<PhotoCategoryAssignment>
    fun findAllByPhotoIdIn(photoIds: Collection<Long>): List<PhotoCategoryAssignment>
    fun deleteAllByDetailFolderId(detailFolderId: Long)
    fun deleteAllByDetailFolderIdIn(detailFolderIds: Collection<Long>)
}

interface CategorizationJobRepository : JpaRepository<CategorizationJob, Long> {
    fun existsByGalleryIdAndModeAndStatus(
        galleryId: Long,
        mode: CategorizationMode,
        status: CategorizationStatus,
    ): Boolean
    fun findFirstByGalleryIdOrderByCreatedAtDesc(galleryId: Long): CategorizationJob?
}

interface CategorizationJobPhotoRepository : JpaRepository<CategorizationJobPhoto, CategorizationJobPhotoId> {
    fun findAllByPhotoIdIn(photoIds: Collection<Long>): List<CategorizationJobPhoto>
    fun countByJobId(jobId: Long): Long
}
