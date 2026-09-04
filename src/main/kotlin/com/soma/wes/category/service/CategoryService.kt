package com.soma.wes.category.service

import com.soma.wes.category.domain.CategorySource
import com.soma.wes.category.domain.ConceptFolder
import com.soma.wes.category.domain.DetailFolder
import com.soma.wes.category.domain.PhotoCategoryAssignment
import com.soma.wes.category.dto.request.CreateConceptFolderRequest
import com.soma.wes.category.dto.request.CreateDetailFolderRequest
import com.soma.wes.category.dto.request.MoveCategoryPhotosRequest
import com.soma.wes.category.dto.response.ConceptFolderResponse
import com.soma.wes.category.dto.response.DetailFolderResponse
import com.soma.wes.category.exception.CategoryErrorCode
import com.soma.wes.category.exception.CategoryException
import com.soma.wes.category.repository.ConceptFolderRepository
import com.soma.wes.category.repository.DetailFolderRepository
import com.soma.wes.category.repository.PhotoCategoryAssignmentRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.repository.PhotoRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CategoryService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val conceptRepository: ConceptFolderRepository,
    private val detailRepository: DetailFolderRepository,
    private val assignmentRepository: PhotoCategoryAssignmentRepository,
    private val photoRepository: PhotoRepository,
    private val reactionCleaner: CategoryReactionCleaner,
    private val clock: Clock,
) {
    @Transactional
    fun createConcept(galleryId: Long, userId: Long, request: CreateConceptFolderRequest): ConceptFolderResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        galleryRepository.requireWithLockById(galleryId)
        val sortOrder = conceptRepository.findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId).size
        val concept = conceptRepository.save(
            ConceptFolder(galleryId, request.name.trim(), sortOrder, CategorySource.USER),
        )
        return ConceptFolderResponse.of(concept, emptyList())
    }

    @Transactional
    fun createDetail(
        galleryId: Long,
        conceptId: Long,
        userId: Long,
        request: CreateDetailFolderRequest,
    ): DetailFolderResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val concept = requireConcept(galleryId, conceptId)
        val sortOrder = detailRepository.findAllByConceptFolderIdOrderBySortOrderAscIdAsc(conceptId).size
        val detail = detailRepository.save(
            DetailFolder(galleryId, concept.requiredId, request.name.trim(), sortOrder, CategorySource.USER),
        )
        return detailResponse(detail, emptyList())
    }

    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<ConceptFolderResponse> {
        galleryAccessPolicy.requireViewer(galleryId, userId)
        val concepts = conceptRepository.findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId)
        val details = detailRepository.findAllByConceptFolderIdIn(concepts.map { it.requiredId })
        val assignments = assignmentRepository.findAllByDetailFolderIdIn(details.map { it.requiredId })
        val photoIdsByDetail = assignments.groupBy { it.detailFolderId }.mapValues { (_, rows) -> rows.map { it.photoId } }
        val detailByConcept = details.groupBy { it.conceptFolderId }
        return concepts.map { concept ->
            ConceptFolderResponse.of(
                concept,
                detailByConcept[concept.requiredId].orEmpty()
                    .sortedWith(compareBy({ it.sortOrder }, { it.requiredId }))
                    .map { detailResponse(it, photoIdsByDetail[it.requiredId].orEmpty()) },
            )
        }
    }

    @Transactional
    fun movePhotos(galleryId: Long, userId: Long, request: MoveCategoryPhotosRequest) {
        galleryAccessPolicy.requireManager(galleryId, userId)
        galleryRepository.requireWithLockById(galleryId)
        if (request.photoIds.isEmpty()) throw CategoryException(CategoryErrorCode.EMPTY_PHOTO_IDS)
        val photoIds = request.photoIds.distinct()
        if (photoRepository.findAllByGalleryIdAndIdIn(galleryId, photoIds).size != photoIds.size) {
            throw CategoryException(CategoryErrorCode.PHOTO_NOT_FOUND)
        }

        val target = request.targetDetailFolderId?.let { targetId ->
            val detail = detailRepository.findByIdAndGalleryId(targetId, galleryId)
                ?: throw CategoryException(CategoryErrorCode.DETAIL_NOT_FOUND)
            detail
        }
        val current = assignmentRepository.findAllByGalleryIdAndPhotoIdIn(galleryId, photoIds).associateBy { it.photoId }
        val currentDetails = detailRepository.findAllById(current.values.map { it.detailFolderId })
            .associateBy { it.requiredId }

        current.values.groupBy { currentDetails[it.detailFolderId]?.conceptFolderId }
            .forEach { (sourceConceptId, rows) ->
                if (sourceConceptId != null && sourceConceptId != target?.conceptFolderId) {
                    reactionCleaner.deleteForConceptExit(sourceConceptId, rows.map { it.photoId })
                }
            }

        val now = ZonedDateTime.now(clock)
        photoIds.forEach { photoId ->
            val existing = current[photoId]
            if (target == null) {
                if (existing != null) assignmentRepository.delete(existing)
            } else if (existing == null) {
                assignmentRepository.save(
                    PhotoCategoryAssignment(galleryId, photoId, target.requiredId, userId, CategorySource.USER, null, now),
                )
            } else {
                existing.moveTo(target.requiredId, userId, now)
            }
        }
    }

    @Transactional
    fun deleteDetail(galleryId: Long, conceptId: Long, detailId: Long, userId: Long) {
        galleryAccessPolicy.requireManager(galleryId, userId)
        requireConcept(galleryId, conceptId)
        val detail = detailRepository.findByIdAndConceptFolderId(detailId, conceptId)
            ?: throw CategoryException(CategoryErrorCode.DETAIL_NOT_FOUND)
        val photoIds = assignmentRepository.findAllByDetailFolderId(detailId).map { it.photoId }
        reactionCleaner.deleteForConceptExit(conceptId, photoIds)
        assignmentRepository.deleteAllByDetailFolderId(detailId)
        detailRepository.delete(detail)
    }

    @Transactional
    fun deleteConcept(galleryId: Long, conceptId: Long, userId: Long) {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val concept = requireConcept(galleryId, conceptId)
        val details = detailRepository.findAllByConceptFolderIdOrderBySortOrderAscIdAsc(conceptId)
        reactionCleaner.deleteForConcept(conceptId)
        assignmentRepository.deleteAllByDetailFolderIdIn(details.map { it.requiredId })
        detailRepository.deleteAll(details)
        conceptRepository.delete(concept)
    }

    private fun requireConcept(galleryId: Long, conceptId: Long): ConceptFolder =
        conceptRepository.findByIdAndGalleryId(conceptId, galleryId)
            ?: throw CategoryException(CategoryErrorCode.CONCEPT_NOT_FOUND)

    private fun detailResponse(detail: DetailFolder, photoIds: List<Long>) = DetailFolderResponse(
        id = detail.requiredId,
        galleryId = detail.galleryId,
        conceptFolderId = detail.conceptFolderId,
        name = detail.name,
        sortOrder = detail.sortOrder,
        createdSource = detail.createdSource,
        category = detail.category,
        needsReview = detail.needsReview,
        photoIds = photoIds,
    )
}
