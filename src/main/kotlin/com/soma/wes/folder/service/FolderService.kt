package com.soma.wes.folder.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.domain.DetailFolder
import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.domain.DetailFolderAssignment
import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.dto.response.DetailFolderResponse
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.folder.service.port.FolderReactionCleaner
import com.soma.wes.folder.support.AiFolderMaterializer
import com.soma.wes.folder.support.FolderViewAssembler
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.repository.PhotoRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FolderService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val conceptRepository: ConceptFolderRepository,
    private val detailRepository: DetailFolderRepository,
    private val assignmentRepository: DetailFolderAssignmentRepository,
    private val photoRepository: PhotoRepository,
    private val reactionCleaner: FolderReactionCleaner,
    private val viewAssembler: FolderViewAssembler,
    private val aiFolderMaterializer: AiFolderMaterializer,
    private val clock: Clock,
    private val activityRecorder: ActivityRecorder,
) {
    @Transactional
    fun createConcept(galleryId: Long, userId: Long, request: CreateConceptFolderRequest): ConceptFolderResponse {
        galleryAccessPolicy.requireFolderEditor(galleryId, userId)
        val sortOrder = conceptRepository.findNextSortOrderByGalleryId(galleryId)
        val concept = conceptRepository.save(
            ConceptFolder(galleryId, request.name.trim(), sortOrder, FolderSource.USER),
        )
        activityRecorder.recordGallery(galleryId)
        return ConceptFolderResponse.of(concept, emptyList())
    }

    @Transactional
    fun createDetail(
        galleryId: Long,
        conceptId: Long,
        userId: Long,
        request: CreateDetailFolderRequest,
    ): DetailFolderResponse {
        galleryAccessPolicy.requireFolderEditor(galleryId, userId)
        val concept = requireConcept(galleryId, conceptId)
        val sortOrder = detailRepository.findNextSortOrderByConceptFolderId(conceptId)
        val detail = detailRepository.save(
            DetailFolder(galleryId, concept.requiredId, request.name.trim(), sortOrder, FolderSource.USER),
        )
        activityRecorder.recordGallery(galleryId)
        // [REFACTOR-A 2026-09-27] private detailResponse(...) → DetailFolderResponse.of
        return DetailFolderResponse.of(detail, emptyList())
    }

    /** 작가의 "AI로 폴더 만들기" 버튼. 인가만 여기서 하고 물질화는 [AiFolderMaterializer]가 같은 트랜잭션에서 한다. */
    @Transactional
    fun createFromAnalysis(galleryId: Long, userId: Long): List<ConceptFolderResponse> {
        galleryAccessPolicy.requireUploader(galleryId, userId)
        return aiFolderMaterializer.materialize(galleryId)
    }

    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<ConceptFolderResponse> {
        galleryAccessPolicy.requireViewer(galleryId, userId)
        val concepts = conceptRepository.findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId)
        // [REFACTOR-A 2026-09-27] 세부 폴더·배정 조회와 조립을 FolderViewAssembler.toResponses로 옮겼다(쿼리·정렬 동일).
        return viewAssembler.toResponses(concepts)
    }

    @Transactional
    fun movePhotos(galleryId: Long, userId: Long, request: MoveFolderPhotosRequest) {
        galleryAccessPolicy.requireFolderEditor(galleryId, userId)
        if (request.photoIds.isEmpty()) throw FolderException(FolderErrorCode.EMPTY_PHOTO_IDS)
        val photoIds = request.photoIds.distinct()
        if (photoRepository.findAllByGalleryIdAndIdIn(galleryId, photoIds).size != photoIds.size) {
            throw FolderException(FolderErrorCode.PHOTO_NOT_FOUND)
        }

        val target = request.targetDetailFolderId?.let { targetId ->
            val detail = detailRepository.findByIdAndGalleryId(targetId, galleryId)
                ?: throw FolderException(FolderErrorCode.DETAIL_NOT_FOUND)
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
                    DetailFolderAssignment(galleryId, photoId, target.requiredId, userId, FolderSource.USER, null, now),
                )
            } else {
                existing.moveTo(target.requiredId, userId, now)
            }
        }
        activityRecorder.recordGallery(galleryId)
    }

    @Transactional
    fun deleteDetail(galleryId: Long, conceptId: Long, detailId: Long, userId: Long) {
        galleryAccessPolicy.requireFolderEditor(galleryId, userId)
        requireConcept(galleryId, conceptId)
        val detail = detailRepository.findByIdAndConceptFolderId(detailId, conceptId)
            ?: throw FolderException(FolderErrorCode.DETAIL_NOT_FOUND)
        val photoIds = assignmentRepository.findAllByDetailFolderId(detailId).map { it.photoId }
        reactionCleaner.deleteForConceptExit(conceptId, photoIds)
        assignmentRepository.deleteAllByDetailFolderId(detailId)
        detailRepository.delete(detail)
        activityRecorder.recordGallery(galleryId)
    }

    @Transactional
    fun deleteConcept(galleryId: Long, conceptId: Long, userId: Long) {
        galleryAccessPolicy.requireFolderEditor(galleryId, userId)
        val concept = requireConcept(galleryId, conceptId)
        val details = detailRepository.findAllByConceptFolderIdOrderBySortOrderAscIdAsc(conceptId)
        reactionCleaner.deleteForConcept(conceptId)
        assignmentRepository.deleteAllByDetailFolderIdIn(details.map { it.requiredId })
        detailRepository.deleteAll(details)
        conceptRepository.delete(concept)
        activityRecorder.recordGallery(galleryId)
    }

    private fun requireConcept(galleryId: Long, conceptId: Long): ConceptFolder =
        conceptRepository.findByIdAndGalleryId(conceptId, galleryId)
            ?: throw FolderException(FolderErrorCode.CONCEPT_NOT_FOUND)
}
