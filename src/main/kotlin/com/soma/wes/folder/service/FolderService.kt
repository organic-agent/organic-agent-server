package com.soma.wes.folder.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.domain.DetailFolder
import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.domain.DetailFolderAssignment
import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MergeDetailFolderRequest
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
        return viewAssembler.toResponses(concepts)
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

    /**
     * 세부 폴더를 다른 세부 폴더에 합친다 — 원본의 사진을 모두 대상으로 옮기고 빈 원본을 지운다.
     * 옮긴 사진은 [movePhotos]처럼 USER 배정이 되어 AI 폴더를 다시 만들어도 원래 폴더로 돌아가지 않고,
     * 다른 컨셉으로 합치면 원본 컨셉에 남긴 협업 반응을 지운다. 응답은 합친 뒤 대상 폴더다.
     */
    @Transactional
    fun mergeDetail(
        galleryId: Long,
        conceptId: Long,
        detailId: Long,
        userId: Long,
        request: MergeDetailFolderRequest,
    ): DetailFolderResponse {
        galleryAccessPolicy.requireFolderEditor(galleryId, userId)
        if (detailId == request.targetDetailFolderId) throw FolderException(FolderErrorCode.MERGE_INTO_SELF)
        requireConcept(galleryId, conceptId)
        val source = detailRepository.findByIdAndConceptFolderId(detailId, conceptId)
            ?: throw FolderException(FolderErrorCode.DETAIL_NOT_FOUND)
        val target = requireDetail(galleryId, request.targetDetailFolderId)

        val movingAssignments = assignmentRepository.findAllByDetailFolderId(detailId)
        deleteReactionsOnConceptExit(movingAssignments, target)
        val now = ZonedDateTime.now(clock)
        for (assignment in movingAssignments) assignment.moveTo(target.requiredId, userId, now)
        detailRepository.delete(source)

        activityRecorder.recordGallery(galleryId)
        val photoIds = assignmentRepository.findAllByDetailFolderId(target.requiredId).map { it.photoId }
        return DetailFolderResponse.of(target, photoIds)
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

    /**
     * 사진을 세부 폴더로 옮기거나, 목적지가 null이면 미분류(배정 행 없음)로 뺀다.
     * 하나라도 잘못된 사진이 섞이면 전체를 거절하고, 컨셉을 벗어나는 사진은 그 컨셉 협업 링크의 반응을 지운다.
     */
    @Transactional
    fun movePhotos(galleryId: Long, userId: Long, request: MoveFolderPhotosRequest) {
        galleryAccessPolicy.requireFolderEditor(galleryId, userId)

        val photoIds = validatePhotoIds(galleryId, request.photoIds)
        val targetDetail = request.targetDetailFolderId?.let { requireDetail(galleryId, it) }

        val assignmentByPhotoId = assignmentRepository.findAllByGalleryIdAndPhotoIdIn(galleryId, photoIds)
            .associateBy { it.photoId }
        deleteReactionsOnConceptExit(assignmentByPhotoId.values, targetDetail)
        if (targetDetail == null) {
            assignmentRepository.deleteAll(assignmentByPhotoId.values)
        } else {
            assignTo(galleryId, userId, targetDetail, photoIds, assignmentByPhotoId)
        }

        activityRecorder.recordGallery(galleryId)
    }

    /** 중복을 걷어낸 id를 돌려준다. 휴지통 사진은 `@SQLRestriction`에 걸러져 "없음"으로 센다. */
    private fun validatePhotoIds(galleryId: Long, requested: List<Long>): List<Long> {
        if (requested.isEmpty()) throw FolderException(FolderErrorCode.EMPTY_PHOTO_IDS)
        val photoIds = requested.distinct()
        if (photoRepository.findAllByGalleryIdAndIdIn(galleryId, photoIds).size != photoIds.size) {
            throw FolderException(FolderErrorCode.PHOTO_NOT_FOUND)
        }
        return photoIds
    }

    private fun requireDetail(galleryId: Long, detailId: Long): DetailFolder =
        detailRepository.findByIdAndGalleryId(detailId, galleryId)
            ?: throw FolderException(FolderErrorCode.DETAIL_NOT_FOUND)

    /**
     * 컨셉 폴더 협업 링크는 컨셉의 현재 배정을 읽어 보여주므로, 컨셉을 떠나는 사진(다른 컨셉·미분류)의 반응은
     * 아무도 볼 수 없는 고아가 된다. 같은 컨셉 안에서 세부 폴더만 바뀌면 같은 화면에 남으니 유지한다.
     */
    private fun deleteReactionsOnConceptExit(
        currentAssignments: Collection<DetailFolderAssignment>,
        targetDetail: DetailFolder?,
    ) {
        val conceptIdByDetailId = detailRepository.findAllById(currentAssignments.map { it.detailFolderId })
            .associate { it.requiredId to it.conceptFolderId }
        val assignmentsBySourceConcept = currentAssignments.groupBy { conceptIdByDetailId[it.detailFolderId] }
        for ((sourceConceptId, leavingAssignments) in assignmentsBySourceConcept) {
            if (sourceConceptId == null || sourceConceptId == targetDetail?.conceptFolderId) continue
            reactionCleaner.deleteForConceptExit(sourceConceptId, leavingAssignments.map { it.photoId })
        }
    }

    /** 사람이 옮긴 배정은 USER가 되어, 이후 AI 폴더 물질화가 이 사진을 다시 배치하지 않는다. */
    private fun assignTo(
        galleryId: Long,
        userId: Long,
        targetDetail: DetailFolder,
        photoIds: List<Long>,
        assignmentByPhotoId: Map<Long, DetailFolderAssignment>,
    ) {
        val now = ZonedDateTime.now(clock)
        for (photoId in photoIds) {
            val currentAssignment = assignmentByPhotoId[photoId]
            if (currentAssignment == null) {
                assignmentRepository.save(
                    DetailFolderAssignment(
                        galleryId = galleryId,
                        photoId = photoId,
                        detailFolderId = targetDetail.requiredId,
                        assignedByUserId = userId,
                        assignedSource = FolderSource.USER,
                        confidence = null,
                        assignedAt = now,
                    ),
                )
            } else {
                currentAssignment.moveTo(targetDetail.requiredId, userId, now)
            }
        }
    }
}
