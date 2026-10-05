package com.soma.wes.folder.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.folder.config.FolderProperties
import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.domain.DetailFolder
import com.soma.wes.folder.domain.DetailFolderMerge
import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.domain.DetailFolderAssignment
import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MergeDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.dto.response.DetailFolderResponse
import com.soma.wes.folder.dto.response.MergeDetailFolderResponse
import com.soma.wes.folder.dto.response.UndoDetailFolderMergeResponse
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.folder.repository.DetailFolderMergeRepository
import com.soma.wes.folder.service.port.FolderReactionCleaner
import com.soma.wes.folder.support.AiFolderMaterializer
import com.soma.wes.folder.support.FolderViewAssembler
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.repository.PhotoRepository
import java.time.Clock
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FolderService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val conceptRepository: ConceptFolderRepository,
    private val detailRepository: DetailFolderRepository,
    private val assignmentRepository: DetailFolderAssignmentRepository,
    private val mergeRepository: DetailFolderMergeRepository,
    private val photoRepository: PhotoRepository,
    private val reactionCleaner: FolderReactionCleaner,
    private val viewAssembler: FolderViewAssembler,
    private val aiFolderMaterializer: AiFolderMaterializer,
    private val folderProperties: FolderProperties,
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
     * 세부 폴더를 다른 세부 폴더에 합친다 — 원본의 사진을 모두 대상으로 옮기고 빈 원본을 숨긴다.
     * 옮긴 사진은 [movePhotos]처럼 USER 배정이 되어 AI 폴더를 다시 만들어도 원래 폴더로 돌아가지 않고,
     * 다른 컨셉으로 합치면 원본 컨셉에 남긴 협업 반응을 지운다.
     *
     * 원본을 지우지 않고 숨기며 옮기기 전 배정을 기록해 두어, [undoMerge]로 되돌릴 수 있다. 응답의 `mergeId`가 그 열쇠다.
     */
    @Transactional
    fun mergeDetail(
        galleryId: Long,
        conceptId: Long,
        detailId: Long,
        userId: Long,
        request: MergeDetailFolderRequest,
    ): MergeDetailFolderResponse {
        galleryAccessPolicy.requireFolderEditor(galleryId, userId)
        if (detailId == request.targetDetailFolderId) throw FolderException(FolderErrorCode.MERGE_INTO_SELF)
        requireConcept(galleryId, conceptId)
        val source = detailRepository.findByIdAndConceptFolderId(detailId, conceptId)
            ?: throw FolderException(FolderErrorCode.DETAIL_NOT_FOUND)
        val target = requireDetail(galleryId, request.targetDetailFolderId)

        // DB는 마이크로초까지 담는다. 되돌릴 때 배정 시각을 이 값과 비교하므로 처음부터 같은 정밀도로 맞춘다.
        val now = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
        val movingAssignments = assignmentRepository.findAllByDetailFolderId(detailId)
        val merge = mergeRepository.save(
            DetailFolderMerge(
                galleryId = galleryId,
                sourceDetailFolderId = source.requiredId,
                targetDetailFolderId = target.requiredId,
                mergedByUserId = userId,
                mergedAt = now,
                movedPhotos = movingAssignments.map { it.snapshot() },
            ),
        )
        deleteReactionsOnConceptExit(movingAssignments, target)
        for (assignment in movingAssignments) assignment.moveTo(target.requiredId, userId, now)
        source.hide(now)

        activityRecorder.recordGallery(galleryId)
        val photoIds = assignmentRepository.findAllByDetailFolderId(target.requiredId).map { it.photoId }
        return MergeDetailFolderResponse(mergeId = merge.requiredId, target = DetailFolderResponse.of(target, photoIds))
    }

    /**
     * 합치기를 되돌린다 — 숨긴 원본 폴더가 원래 id · 순서 · 출처로 돌아오고, 옮긴 사진이 옮기기 전 배정 정보 그대로 원본으로 돌아간다.
     * 웹의 "실행 취소"용이라 [FolderProperties.mergeUndoWindow] 안에서 한 번만 된다.
     *
     * 그 사이 옮긴 사진이 한 장이라도 다른 곳으로 옮겨졌거나 원본의 컨셉이 사라졌으면 거절한다 — 되돌리면 그 변경을 덮어쓰기 때문이다.
     * 다른 컨셉으로 합칠 때 지운 협업 반응은 돌아오지 않는다. 반대로 대상 컨셉을 떠나는 사진의 반응은 [movePhotos]처럼 지운다.
     */
    @Transactional
    fun undoMerge(galleryId: Long, mergeId: Long, userId: Long): UndoDetailFolderMergeResponse {
        galleryAccessPolicy.requireFolderEditor(galleryId, userId)
        val merge = mergeRepository.findWithLockByIdAndGalleryId(mergeId, galleryId)
            ?: throw FolderException(FolderErrorCode.MERGE_NOT_FOUND)

        val now = ZonedDateTime.now(clock)
        if (merge.isUndone) throw FolderException(FolderErrorCode.MERGE_ALREADY_UNDONE)
        if (merge.isUndoExpired(now, folderProperties.mergeUndoWindow)) throw FolderException(FolderErrorCode.MERGE_UNDO_EXPIRED)
        val source = detailRepository.findHiddenByIdAndGalleryId(merge.sourceDetailFolderId, galleryId)
            ?.takeIf { conceptRepository.findByIdAndGalleryId(it.conceptFolderId, galleryId) != null }
            ?: throw FolderException(FolderErrorCode.MERGE_UNDO_CONFLICT)
        val target = detailRepository.findByIdAndGalleryId(merge.targetDetailFolderId, galleryId)
            ?: throw FolderException(FolderErrorCode.MERGE_UNDO_CONFLICT)
        val snapshotByPhotoId = merge.movedPhotos.associateBy { it.photoId }
        val movedAssignments = assignmentRepository.findAllByGalleryIdAndPhotoIdIn(galleryId, snapshotByPhotoId.keys)
        if (movedAssignments.size != snapshotByPhotoId.size || movedAssignments.any { !it.isStillMergedBy(merge) }) {
            throw FolderException(FolderErrorCode.MERGE_UNDO_CONFLICT)
        }

        deleteReactionsOnConceptExit(movedAssignments, source)
        source.unhide()
        for (assignment in movedAssignments) assignment.restore(source.requiredId, snapshotByPhotoId.getValue(assignment.photoId))
        merge.markUndone(now)

        activityRecorder.recordGallery(galleryId)
        val targetPhotoIds = assignmentRepository.findAllByDetailFolderId(target.requiredId).map { it.photoId }
        return UndoDetailFolderMergeResponse(
            source = DetailFolderResponse.of(source, movedAssignments.map { it.photoId }),
            target = DetailFolderResponse.of(target, targetPhotoIds),
        )
    }

    /** 합친 그대로인가 — 대상 폴더에 있고, 배정 시각이 합친 시각이다. 그 뒤 누가 옮겼다 되돌려 놓았어도 시각이 바뀐다. */
    private fun DetailFolderAssignment.isStillMergedBy(merge: DetailFolderMerge): Boolean =
        detailFolderId == merge.targetDetailFolderId && assignedAt.toInstant() == merge.mergedAt.toInstant()

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
