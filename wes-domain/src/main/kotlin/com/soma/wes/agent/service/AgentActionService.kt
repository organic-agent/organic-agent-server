package com.soma.wes.agent.service

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentActionStep
import com.soma.wes.agent.dto.AgentAppliedStepDto
import com.soma.wes.agent.dto.request.AgentActionRequest
import com.soma.wes.agent.dto.response.AgentActionResponse
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.dto.request.RenameFolderRequest
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.folder.service.FolderService
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.selection.dto.request.DeselectPhotosRequest
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.selection.service.PhotoSelectionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 셀렉 도우미의 실행 창구. 사용자가 확인한 계획, 또는 직전 실행의 되돌리기를 실행한다 — 둘은 같은 모양이다.
 *
 * 쓰기는 전부 기존 서비스(`PhotoSelectionService`·`FolderService` — 넣기·빼기·옮기기·이름 바꾸기)로 한다. 권한·계약 장수·제출 여부 검사를 다시 짜지 않고,
 * 사람이 버튼으로 한 것과 똑같이 거절된다. 계획은 서버에 저장하지 않는다 — 같은 사용자가 기존 API로도 똑같이 할 수 있는 일이라
 * 위조를 막을 이유가 없고, 저장하면 만료·청소가 생긴다(계획 문서 D2-b).
 *
 * 되돌리기는 실행하는 순간에 실제로 바뀐 것으로 만든다. 계획을 만든 뒤 배우자가 바꾼 것까지 덮어쓰지 않기 위해서다.
 * 이름 바꾸기는 실행 순간의 이름이 계획의 [AgentActionStep.expectedName]과 다르면 건너뛴다(QA-11).
 * 한계: 빼고 다시 넣은 사진은 앨범 맨 뒤로 가고, 이동을 되돌린 사진의 배정 출처는 사람(USER)이 된다.
 * 옮기기 되돌리기는 그사이 배우자가 옮긴 사진도 이전 폴더로 되돌린다 — 이름 바꾸기와 달리 확인하지 않는다(#403 범위 밖).
 */
@Service
class AgentActionService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoSelectionService: PhotoSelectionService,
    private val folderService: FolderService,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val detailFolderAssignmentRepository: DetailFolderAssignmentRepository,
    private val conceptFolderRepository: ConceptFolderRepository,
    private val detailFolderRepository: DetailFolderRepository,
    private val storageProperties: StorageProperties,
) {

    /** 동작을 순서대로 한 트랜잭션에서 실행한다. 하나라도 거절되면 아무것도 바뀌지 않는다. */
    @Transactional
    fun execute(galleryId: Long, userId: Long, request: AgentActionRequest): AgentActionResponse {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)

        validateSteps(request.steps)

        val results = request.steps.mapNotNull { step -> apply(galleryId = galleryId, userId = userId, step = step) }

        return AgentActionResponse(
            applied = results.map { it.applied },
            undo = AgentActionRequest(steps = results.reversed().flatMap { it.undo }),
        )
    }

    private fun validateSteps(steps: List<AgentActionStep>) {
        if (steps.isEmpty()) throw AgentException(AgentErrorCode.EMPTY_ACTION)
        val invalid = steps.size > AgentActionRequest.MAX_STEPS || steps.any { step -> !isWellFormed(step) }
        if (invalid) throw AgentException(AgentErrorCode.INVALID_ACTION)
    }

    /** 사진 동작은 사진이 있고 폴더 이름 칸이 비어 있다. 이름 바꾸기는 사진이 없고 폴더·새 이름·예상 이름이 있다. */
    private fun isWellFormed(step: AgentActionStep): Boolean {
        val renameFields = listOf(step.conceptFolderId, step.detailFolderId, step.name, step.expectedName)
        return when (step.op) {
            AgentActionOp.SELECT_ADD, AgentActionOp.SELECT_REMOVE ->
                step.photoIds.isNotEmpty() && step.targetDetailFolderId == null && renameFields.all { it == null }
            AgentActionOp.MOVE -> step.photoIds.isNotEmpty() && renameFields.all { it == null }
            AgentActionOp.RENAME_CONCEPT -> isRenameShape(step) && step.detailFolderId == null
            AgentActionOp.RENAME_DETAIL -> isRenameShape(step) && step.detailFolderId != null
        }
    }

    private fun isRenameShape(step: AgentActionStep): Boolean =
        step.photoIds.isEmpty() && step.targetDetailFolderId == null && step.conceptFolderId != null &&
            !step.name.isNullOrBlank() && step.expectedName != null

    private fun apply(galleryId: Long, userId: Long, step: AgentActionStep): AgentAppliedStepDto? =
        when (step.op) {
            AgentActionOp.SELECT_ADD -> selectAdd(galleryId = galleryId, userId = userId, photoIds = step.photoIds.distinct())
            AgentActionOp.SELECT_REMOVE -> selectRemove(galleryId = galleryId, userId = userId, photoIds = step.photoIds.distinct())
            AgentActionOp.MOVE -> move(
                galleryId = galleryId,
                userId = userId,
                photoIds = step.photoIds.distinct(),
                targetDetailFolderId = step.targetDetailFolderId,
            )
            AgentActionOp.RENAME_CONCEPT, AgentActionOp.RENAME_DETAIL -> rename(galleryId = galleryId, userId = userId, step = step)
        }

    /** 이미 담긴 사진은 건너뛴다. 담기 한 번의 상한(`app.storage.max-batch-size`)을 넘으면 나눠 담는다. */
    private fun selectAdd(galleryId: Long, userId: Long, photoIds: List<Long>): AgentAppliedStepDto? {
        val selected = selectedPhotoIds(galleryId)
        val adding = photoIds.filter { it !in selected }
        if (adding.isEmpty()) return null

        for (chunk in adding.chunked(storageProperties.maxBatchSize)) {
            photoSelectionService.select(galleryId = galleryId, userId = userId, request = SelectPhotosRequest(photoIds = chunk))
        }

        return AgentAppliedStepDto(
            applied = AgentActionStep(op = AgentActionOp.SELECT_ADD, photoIds = adding),
            undo = listOf(AgentActionStep(op = AgentActionOp.SELECT_REMOVE, photoIds = adding)),
        )
    }

    private fun selectRemove(galleryId: Long, userId: Long, photoIds: List<Long>): AgentAppliedStepDto? {
        val selected = selectedPhotoIds(galleryId)
        val removing = photoIds.filter { it in selected }
        if (removing.isEmpty()) return null

        photoSelectionService.deselect(galleryId = galleryId, userId = userId, request = DeselectPhotosRequest(photoIds = removing))

        return AgentAppliedStepDto(
            applied = AgentActionStep(op = AgentActionOp.SELECT_REMOVE, photoIds = removing),
            undo = listOf(AgentActionStep(op = AgentActionOp.SELECT_ADD, photoIds = removing)),
        )
    }

    private fun selectedPhotoIds(galleryId: Long): Set<Long> {
        val selection = photoSelectionRepository.findByGalleryId(galleryId) ?: return emptySet()
        return photoSelectionItemRepository.findAllBySelectionId(selection.requiredId).map { it.photoId }.toSet()
    }

    /** 이미 그 폴더에 있는 사진은 건너뛴다. 되돌리기는 이전 폴더(미분류 포함)마다 이동 하나다. */
    private fun move(galleryId: Long, userId: Long, photoIds: List<Long>, targetDetailFolderId: Long?): AgentAppliedStepDto? {
        val previousFolderByPhotoId = detailFolderAssignmentRepository.findAllByGalleryIdAndPhotoIdIn(galleryId, photoIds)
            .associate { it.photoId to it.detailFolderId }
        val moving = photoIds.filter { previousFolderByPhotoId[it] != targetDetailFolderId }
        if (moving.isEmpty()) return null

        folderService.movePhotos(
            galleryId = galleryId,
            userId = userId,
            request = MoveFolderPhotosRequest(photoIds = moving, targetDetailFolderId = targetDetailFolderId),
        )

        return AgentAppliedStepDto(
            applied = AgentActionStep(op = AgentActionOp.MOVE, photoIds = moving, targetDetailFolderId = targetDetailFolderId),
            undo = moving.groupBy { previousFolderByPhotoId[it] }.map { (previousFolderId, ids) ->
                AgentActionStep(op = AgentActionOp.MOVE, photoIds = ids, targetDetailFolderId = previousFolderId)
            },
        )
    }

    /**
     * 갤러리 행을 먼저 잠가([GalleryAccessPolicy.requireFolderEditor]) 배우자의 이름 바꾸기와 줄을 세운 뒤 지금 이름을 읽는다.
     * 지금 이름이 계획의 예상 이름과 다르면 그사이 누군가 바꾼 것이라 건너뛰고, 이미 새 이름이면 바꿀 것이 없다.
     * 되돌리기는 "지금 새 이름일 때만 원래 이름으로"다.
     */
    private fun rename(galleryId: Long, userId: Long, step: AgentActionStep): AgentAppliedStepDto? {
        val conceptId = step.conceptFolderId ?: throw AgentException(AgentErrorCode.INVALID_ACTION)
        val name = step.name ?: throw AgentException(AgentErrorCode.INVALID_ACTION)
        galleryAccessPolicy.requireFolderEditor(galleryId, userId)

        val currentName = currentFolderName(galleryId = galleryId, conceptId = conceptId, detailId = step.detailFolderId)
        if (currentName != step.expectedName || currentName == name) return null

        val renamed = when (val detailId = step.detailFolderId) {
            null -> folderService.renameConcept(galleryId, conceptId, userId, RenameFolderRequest(name = name)).name
            else -> folderService.renameDetail(
                galleryId = galleryId,
                conceptId = conceptId,
                detailId = detailId,
                userId = userId,
                request = RenameFolderRequest(name = name),
            ).name
        }

        return AgentAppliedStepDto(
            applied = step.copy(name = renamed, expectedName = currentName),
            undo = listOf(step.copy(name = currentName, expectedName = renamed)),
        )
    }

    private fun currentFolderName(galleryId: Long, conceptId: Long, detailId: Long?): String {
        if (detailId == null) {
            return conceptFolderRepository.findByIdAndGalleryId(conceptId, galleryId)?.name
                ?: throw FolderException(FolderErrorCode.CONCEPT_NOT_FOUND)
        }
        val detail = detailFolderRepository.findByIdAndGalleryId(detailId, galleryId)
            ?.takeIf { it.conceptFolderId == conceptId }
            ?: throw FolderException(FolderErrorCode.DETAIL_NOT_FOUND)
        return detail.name
    }
}
