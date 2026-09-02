package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.dto.request.ChangeMaxRetouchRoundCountRequest
import com.soma.wes.gallery.dto.request.ChangeMaxSelectablePhotoCountRequest
import com.soma.wes.gallery.dto.request.ChangeSelectionDeadlineRequest
import com.soma.wes.gallery.dto.request.ChangeShootTypeRequest
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.request.ChangeWorkflowStatusRequest
import com.soma.wes.gallery.dto.request.RenameGalleryRequest
import com.soma.wes.gallery.dto.request.ReopenGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime


@Service
class GalleryService(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioRepository: StudioRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val clock: Clock,
) {

    @Transactional
    fun create(userId: Long, request: CreateGalleryRequest): GalleryResponse {
        requireOperatingWorkspace(request.workspaceId, userId)

        val gallery = galleryRepository.save(
            Gallery.create(
                workspaceId = request.workspaceId,
                createdByUserId = userId,
                title = request.title,
                selectionDeadline = request.selectionDeadline,
                maxSelectablePhotoCount = request.maxSelectablePhotoCount,
                maxRetouchRoundCount = request.maxRetouchRoundCount,
                shootType = request.shootType,
                at = ZonedDateTime.now(clock),
            ),
        )
        return GalleryResponse.from(gallery)
    }

    @Transactional(readOnly = true)
    fun findAllVisibleTo(userId: Long, stage: com.soma.wes.gallery.domain.GalleryStage? = null): List<GalleryResponse> {
        val operatingWorkspaceIds = workspaceMemberRepository
            .findAllByUserIdAndRoleIn(userId, WorkspaceRole.entries)
            .map { it.workspaceId }
            .toSet()
        val asManager = if (operatingWorkspaceIds.isEmpty()) {
            emptyList()
        } else {
            galleryRepository.findAllByStudioIdIn(operatingWorkspaceIds)
        }

        val memberGalleryIds = galleryMemberRepository.findAllByUserId(userId).map { it.galleryId }
        val asCouple = galleryRepository.findAllById(memberGalleryIds)
            .filter { it.isVisibleToMember }

        // 자기 갤러리 초대는 GalleryAccessPolicy.requireNotPhotographer가 막지만,
        // 그 규칙이 생기기 전 데이터까지 같은 갤러리를 두 번 그리게 두지는 않는다.
        return (asManager + asCouple)
            .distinctBy { it.requiredId }
            .filter { stage == null || it.stage == stage }
            .map(GalleryResponse::from)
    }

    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): GalleryResponse =
        GalleryResponse.from(galleryAccessPolicy.requireViewer(galleryId, userId))

    @Transactional
    fun changeMaxSelectablePhotoCount(
        galleryId: Long,
        userId: Long,
        request: ChangeMaxSelectablePhotoCountRequest,
    ): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.changeMaxSelectablePhotoCount(request.maxSelectablePhotoCount)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun changeMaxRetouchRoundCount(
        galleryId: Long,
        userId: Long,
        request: ChangeMaxRetouchRoundCountRequest,
    ): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.changeMaxRetouchRoundCount(request.maxRetouchRoundCount)
        return GalleryResponse.from(gallery)
    }

    /** 촬영 종류만 바꾼다. 이미 만든 AI 폴더는 그대로다 — 새 목록은 다음 NAMING 잡부터 반영된다. */
    @Transactional
    fun changeShootType(galleryId: Long, userId: Long, request: ChangeShootTypeRequest): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.changeShootType(request.shootType)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun rename(galleryId: Long, userId: Long, request: RenameGalleryRequest): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.rename(request.title)
        return GalleryResponse.from(gallery)
    }

    /**
     * 마감 기한만 바꾼다. 상태는 건드리지 않는다 — CLOSED 갤러리의 기한을 바꿔도 다시
     * 열리지 않으며, 다시 여는 것은 재오픈([reopen])의 일이다.
     */
    @Transactional
    fun changeSelectionDeadline(
        galleryId: Long,
        userId: Long,
        request: ChangeSelectionDeadlineRequest,
    ): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.changeSelectionDeadline(request.selectionDeadline, ZonedDateTime.now(clock))
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun open(galleryId: Long, userId: Long): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.open()
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun close(galleryId: Long, userId: Long): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.close()
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun changeWorkflowStatus(
        galleryId: Long,
        userId: Long,
        request: ChangeWorkflowStatusRequest,
    ): GalleryResponse {
        val gallery = galleryAccessPolicy.requireManager(galleryId, userId)
        gallery.changeWorkflowStatus(request.workflowStatus)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun reopen(galleryId: Long, userId: Long, request: ReopenGalleryRequest): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.reopen(request.selectionDeadline, ZonedDateTime.now(clock))
        return GalleryResponse.from(gallery)
    }

    /**
     * 갤러리를 휴지통으로 보낸다. 담당 작가만 할 수 있다.
     *
     * 사진 행은 건드리지 않는다. 갤러리 조회가 전부 [GalleryAccessPolicy]의 `findById` 관문을
     * 지나므로, 갤러리 하나가 숨는 것으로 그 안의 사진·폴더·앨범·협업 링크가 모두 404가 된다.
     * 그래야 복원이 지우기 전 모습 그대로 되살리고, 갤러리보다 먼저 개별 삭제된 사진은
     * 복원 뒤에도 휴지통에 남는다. 복원·물리 삭제는 trash 도메인이 담당한다.
     */
    @Transactional
    fun moveToTrash(galleryId: Long, userId: Long) {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.moveToTrash(ZonedDateTime.now(clock))
    }

    private fun requireOperatingWorkspace(workspaceId: Long, userId: Long) {
        if (!workspaceMemberRepository.existsByWorkspaceIdAndUserIdAndRoleIn(
                workspaceId,
                userId,
                WorkspaceRole.entries,
            )
        ) {
            throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        }
        if (studioRepository.existsById(workspaceId) &&
            !studioRepository.existsByIdAndSuspendedAtIsNull(workspaceId)
        ) {
            throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        }
    }
}
