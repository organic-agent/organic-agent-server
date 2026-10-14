package com.soma.wes.gallery.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.gallery.config.GalleryLifecycleProperties
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.dto.request.RequestSelectionIncreaseRequest
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.workspace.repository.WorkspaceRepository
import com.soma.wes.workspace.domain.WorkspaceType
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
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.repository.PhotoSelectionRepository
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
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val clock: Clock,
    private val notificationPublisher: UserNotificationPublisher,
    private val workspaceRepository: WorkspaceRepository,
    private val lifecycleProperties: GalleryLifecycleProperties,
    private val activityRecorder: ActivityRecorder,
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
        photoSelectionRepository.save(PhotoSelection(galleryId = gallery.requiredId))
        activityRecorder.recordGallery(gallery.requiredId)
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
            galleryRepository.findAllByWorkspaceIdIn(operatingWorkspaceIds)
        }

        val memberGalleryIds = galleryMemberRepository.findAllByUserId(userId).map { it.galleryId }
        val asCouple = galleryRepository.findAllById(memberGalleryIds)

        // 자기 갤러리 초대는 GalleryAccessPolicy.requireNotManager가 막지만,
        // 그 규칙이 생기기 전 데이터까지 같은 갤러리를 두 번 그리게 두지는 않는다.
        return (asManager + asCouple)
            .distinctBy { it.requiredId }
            .filter { stage == null || it.stage == stage }
            .map(GalleryResponse::from)
    }

    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): GalleryResponse =
        GalleryResponse.from(galleryAccessPolicy.requireMetadataViewer(galleryId, userId))

    @Transactional
    fun changeMaxSelectablePhotoCount(
        galleryId: Long,
        userId: Long,
        request: ChangeMaxSelectablePhotoCountRequest,
    ): GalleryResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.requireWritable(ZonedDateTime.now(clock))

        gallery.changeMaxSelectablePhotoCount(request.maxSelectablePhotoCount)
        publishToClients(gallery, UserNotificationType.SELECTION_INCREASE_APPROVED, "목표 장수가 변경되었어요", "변경된 목표 장수: ${request.maxSelectablePhotoCount ?: "제한 없음"}")
        activityRecorder.recordGallery(gallery.requiredId)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun changeMaxRetouchRoundCount(
        galleryId: Long,
        userId: Long,
        request: ChangeMaxRetouchRoundCountRequest,
    ): GalleryResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.requireWritable(ZonedDateTime.now(clock))

        gallery.changeMaxRetouchRoundCount(request.maxRetouchRoundCount)
        activityRecorder.recordGallery(gallery.requiredId)
        return GalleryResponse.from(gallery)
    }

    /** 촬영 종류만 바꾼다. 이미 만든 AI 폴더는 그대로다 — 새 목록은 다음 NAMING 잡부터 반영된다. */
    @Transactional
    fun changeShootType(galleryId: Long, userId: Long, request: ChangeShootTypeRequest): GalleryResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.requireWritable(ZonedDateTime.now(clock))

        gallery.changeShootType(request.shootType)
        activityRecorder.recordGallery(gallery.requiredId)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun rename(galleryId: Long, userId: Long, request: RenameGalleryRequest): GalleryResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.requireWritable(ZonedDateTime.now(clock))

        gallery.rename(request.title)
        activityRecorder.recordGallery(gallery.requiredId)
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
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.requireWritable(ZonedDateTime.now(clock))

        if (request.selectionDeadline != null && gallery.planExpiresAt?.let { request.selectionDeadline.isAfter(it) } == true) {
            throw GalleryException(GalleryErrorCode.INVALID_SELECTION_DEADLINE)
        }
        gallery.changeSelectionDeadline(request.selectionDeadline, ZonedDateTime.now(clock))
        activityRecorder.recordGallery(gallery.requiredId)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun open(galleryId: Long, userId: Long): GalleryResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.requireWritable(ZonedDateTime.now(clock))

        gallery.open()
        publishToClients(gallery, UserNotificationType.GALLERY_OPENED, "갤러리가 열렸어요", "사진 정리를 시작해 주세요.")
        activityRecorder.recordGallery(gallery.requiredId)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun close(galleryId: Long, userId: Long): GalleryResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)

        gallery.close()
        if (gallery.archivedUntil == null) lifecycleProperties.archivedRetentionDays?.let {
            gallery.archivedUntil = ZonedDateTime.now(clock).plusDays(it.toLong())
        }
        activityRecorder.recordGallery(gallery.requiredId)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun changeWorkflowStatus(
        galleryId: Long,
        userId: Long,
        request: ChangeWorkflowStatusRequest,
    ): GalleryResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.requireWritable(ZonedDateTime.now(clock))
        gallery.changeWorkflowStatus(request.workflowStatus)
        activityRecorder.recordGallery(gallery.requiredId)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun reopen(galleryId: Long, userId: Long, request: ReopenGalleryRequest): GalleryResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.requireWritable(ZonedDateTime.now(clock))

        gallery.reopen(request.selectionDeadline, ZonedDateTime.now(clock))
        publishToClients(gallery, UserNotificationType.GALLERY_REOPENED, "선택이 다시 열렸어요", "새 마감 기한을 확인해 주세요.")
        activityRecorder.recordGallery(gallery.requiredId)
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
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)

        activityRecorder.recordGallery(galleryId)
        gallery.moveToTrash(ZonedDateTime.now(clock))
    }

    @Transactional
    fun requestSelectionIncrease(galleryId: Long, userId: Long, request: RequestSelectionIncreaseRequest) {
        galleryAccessPolicy.requireSelectionEditor(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        photoSelectionRepository.findByGalleryId(galleryId)?.requireEditable()
        if (workspaceRepository.findById(gallery.workspaceId).orElse(null)?.type != WorkspaceType.STUDIO ||
            gallery.maxSelectablePhotoCount == null || request.requestedCount <= gallery.maxSelectablePhotoCount!! ||
            request.message.orEmpty().length > 300
        ) throw GalleryException(GalleryErrorCode.INVALID_INCREASE_REQUEST)
        notificationPublisher.publish(
            userIds = workspaceMemberRepository.findAllByWorkspaceId(gallery.workspaceId).map { it.userId },
            type = UserNotificationType.SELECTION_INCREASE_REQUESTED, scope = UserNotificationScope.GALLERY,
            scopeId = galleryId, title = "계약 장수 상향 요청", message = "${request.requestedCount}장 요청: ${request.message.orEmpty()}",
        )
        activityRecorder.recordGallery(galleryId)
    }

    private fun publishToClients(gallery: Gallery, type: UserNotificationType, title: String, message: String) {
        notificationPublisher.publish(
            userIds = galleryMemberRepository.findAllByGalleryId(gallery.requiredId).map { it.userId },
            type = type, scope = UserNotificationScope.GALLERY, scopeId = gallery.requiredId, title = title, message = message,
        )
    }

    private fun requireOperatingWorkspace(workspaceId: Long, userId: Long) {
        val workspace = workspaceRepository.findById(workspaceId).orElse(null)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        if (!workspaceMemberRepository.existsByWorkspaceIdAndUserIdAndRoleIn(
                workspaceId,
                userId,
                WorkspaceRole.entries,
            )
        ) {
            throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        }
        if (workspace.type == WorkspaceType.PERSONAL) {
            throw GalleryException(GalleryErrorCode.PERSONAL_CHECKOUT_REQUIRED)
        }
        if (studioRepository.existsById(workspaceId) &&
            !studioRepository.existsByIdAndSuspendedAtIsNull(workspaceId)
        ) {
            throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        }
    }
}
