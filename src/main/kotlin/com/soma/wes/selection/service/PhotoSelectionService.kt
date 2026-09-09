package com.soma.wes.selection.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.photo.support.PhotoViewAssembler
import com.soma.wes.retouch.support.RetouchResultLoader
import com.soma.wes.retouch.service.RetouchRequestService
import com.soma.wes.retouch.repository.RetouchRoundRepository
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.domain.RetouchRoundStatus
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.dto.request.SubmitRetouchRequestsRequest
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.domain.PhotoSelectionItem
import com.soma.wes.selection.dto.request.DeselectPhotosRequest
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.dto.response.PhotoSelectionResponse
import com.soma.wes.selection.dto.response.SelectedPhotoResponse
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 예비 부부가 최종적으로 고른 사진을 담는 선택 앨범.
 */
@Service
class PhotoSelectionService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val retouchResultLoader: RetouchResultLoader,
    private val photoStorage: PhotoStorage,
    private val properties: StorageProperties,
    private val clock: Clock,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val notificationPublisher: UserNotificationPublisher,
    private val retouchRequestWriter: RetouchRequestService,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val retouchRoundRepository: RetouchRoundRepository,
    private val retouchPhotoRepository: RetouchPhotoRepository,
    private val activityRecorder: ActivityRecorder,
) {

    /**
     * 앨범을 연다. 마감 뒤에도, 제출 뒤에도 보인다.
     */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): PhotoSelectionResponse {
        val gallery = galleryAccessPolicy.requireViewer(galleryId, userId)

        val selection = photoSelectionRepository.findByGalleryId(galleryId)
        val response = responseOf(gallery, selection)
        if (galleryAccessPolicy.isStudioManager(galleryId, userId)) {
            if (selection?.isSubmitted != true) return response.copy(photos = emptyList())
            return response.copy(photos = response.photos.map { it.copy(photo = it.photo.copy(score = null)) })
        }
        return response
    }

    /**
     * 고른 사진을 담는다. 원본(photoIds)과 보정본(retouchPhotos)을 함께 담을 수 있다 —
     * 어느 쪽으로 담아도 항목은 원본을 가리키므로 중복·정원 규칙은 원본 기준 하나다.
     */
    @Transactional
    fun select(galleryId: Long, userId: Long, request: SelectPhotosRequest): PhotoSelectionResponse {
        galleryAccessPolicy.requireSelectionEditor(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        requireSelectionNotExported(gallery)
        if (gallery.photoOrganizationRequired && gallery.foldersSavedAt == null) {
            throw SelectionException(SelectionErrorCode.PHOTO_ORGANIZATION_REQUIRED)
        }
        val selection = loadOrCreate(galleryId)
        selection.requireEditable()

        val retouchPhotoIdByPhotoId = retouchPhotoIdByPhotoId(request)
        val photos = loadSelectablePhotos(galleryId, request.photoIds + retouchPhotoIdByPhotoId.keys)
        retouchResultLoader.validateSelectable(galleryId, retouchPhotoIdByPhotoId)
        val alreadySelected = photoSelectionItemRepository.findAllBySelectionId(selection.requiredId)
        val alreadySelectedPhotoIds = alreadySelected.map { it.photoId }.toSet()

        // 중복이 먼저다. 겹친 채로 장수를 세면 "몇 장이 넘쳤다"가 실제와 다르고, 사용자는
        // 담기지도 않은 사진 때문에 계약 장수를 넘겼다는 말을 듣는다.
        selection.requireNotSelected(alreadySelectedPhotoIds, photos.map { it.requiredId })
        selection.requireWithinMax(gallery.maxSelectablePhotoCount, alreadySelectedPhotoIds.size + photos.size)

        val firstSortOrder = (alreadySelected.maxOfOrNull { it.sortOrder } ?: -1) + 1

        photoSelectionItemRepository.saveAll(
            photos.mapIndexed { index, photo ->
                PhotoSelectionItem(
                    galleryId = galleryId,
                    selectionId = selection.requiredId,
                    photoId = photo.requiredId,
                    addedByUserId = userId,
                    sortOrder = firstSortOrder + index,
                    retouchPhotoId = retouchPhotoIdByPhotoId[photo.requiredId],
                )
            },
        )

        activityRecorder.recordGallery(galleryId)
        return responseOf(gallery, selection)
    }

    /**
     * 보정본 담기 요청을 원본 photoId → retouchPhotoId 매핑으로 편다. 같은 원본을 두 번 담는
     * 요청(retouchPhotos 안의 중복, photoIds와의 겹침)은 이미 담긴 사진과 같은 이유로 거절한다.
     */
    private fun retouchPhotoIdByPhotoId(request: SelectPhotosRequest): Map<Long, Long> {
        val photoIds = request.retouchPhotos.map { it.photoId }
        if (photoIds.size != photoIds.toSet().size || photoIds.any { it in request.photoIds }) {
            throw SelectionException(SelectionErrorCode.PHOTO_ALREADY_SELECTED)
        }
        return request.retouchPhotos.associate { it.photoId to it.retouchPhotoId }
    }

    /**
     * 여러 장을 한 번에 뺀다.
     */
    @Transactional
    fun deselect(galleryId: Long, userId: Long, request: DeselectPhotosRequest): PhotoSelectionResponse {
        galleryAccessPolicy.requireSelectionEditor(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        requireSelectionNotExported(gallery)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: return responseOf(gallery, null)
        selection.requireEditable()

        photoSelectionItemRepository.deleteAllBySelectionIdAndPhotoIdIn(
            selection.requiredId,
            request.photoIds.toSet(),
        )

        activityRecorder.recordGallery(galleryId)
        return responseOf(gallery, selection)
    }

    /**
     * 한 장을 빼낸다. 앨범에 없으면 404다.
     */
    @Transactional
    fun deselectPhoto(galleryId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requireSelectionEditor(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        requireSelectionNotExported(gallery)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: throw SelectionException(SelectionErrorCode.PHOTO_NOT_SELECTED)
        selection.requireEditable()

        if (photoSelectionItemRepository.deleteBySelectionIdAndPhotoId(selection.requiredId, photoId) == 0L) {
            throw SelectionException(SelectionErrorCode.PHOTO_NOT_SELECTED)
        }
        activityRecorder.recordGallery(galleryId)
    }

    /**
     * 부부가 고르기를 끝내고 작가에게 넘긴다.
     */
    @Transactional
    fun submit(
        galleryId: Long,
        userId: Long,
        request: SubmitRetouchRequestsRequest = SubmitRetouchRequestsRequest(),
    ): PhotoSelectionResponse {
        galleryAccessPolicy.requireSelectionEditor(galleryId, userId)
        if (galleryAccessPolicy.isPersonalGallery(galleryId)) {
            throw SelectionException(SelectionErrorCode.PERSONAL_EXPORT_REQUIRED)
        }

        val gallery = galleryRepository.requireWithLockById(galleryId)
        requireSelectionNotExported(gallery)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: throw SelectionException(SelectionErrorCode.EMPTY_SELECTION)

        // 항목 행이 아니라 실제로 남아 있는 사진을 센다. 응답에 실리는 목록과 같은 기준이어야
        // "몇 장을 제출했는지"를 두 값이 다르게 말하지 않는다.
        val items = photoSelectionItemRepository.findAllBySelectionId(selection.requiredId)
        val photos = selectedPhotoResponses(selection.galleryId, items)
        selection.requireEditable()
        selection.requireExactTarget(gallery.maxSelectablePhotoCount, photos.size)
        selection.submit(photos.size, userId, ZonedDateTime.now(clock))
        val latestRound = retouchRoundRepository.findFirstByGalleryIdOrderByRoundNoDesc(galleryId)
        if (latestRound == null || latestRound.isDrafting) {
            retouchRequestWriter.submit(
                gallery = gallery,
                roundNo = latestRound?.roundNo ?: 1,
                photoIds = photos.map { it.photo.photoId },
                requests = request.requests,
                at = ZonedDateTime.now(clock),
            )
        } else if (request.requests.isNotEmpty()) {
            throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
        }
        gallery.markSelectionCompleted()
        activityRecorder.recordGallery(galleryId)
        notificationPublisher.publish(
            userIds = workspaceMemberRepository.findAllByWorkspaceId(gallery.workspaceId)
                .map { it.userId },
            type = UserNotificationType.SELECTION_SUBMITTED,
            scope = UserNotificationScope.GALLERY,
            scopeId = galleryId,
            title = "사진 선택이 제출되었습니다",
            message = "${gallery.title}의 사진 선택이 완료되었습니다.",
        )

        return PhotoSelectionResponse.of(
            selection = selection,
            maxSelectablePhotoCount = gallery.maxSelectablePhotoCount,
            photos = photos,
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    /**
     * 제출을 되돌려 부부가 다시 고를 수 있게 한다. 담당 작가만 할 수 있다.
     */
    @Transactional
    fun withdraw(galleryId: Long, userId: Long): PhotoSelectionResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: throw SelectionException(SelectionErrorCode.SELECTION_NOT_SUBMITTED)

        val latestRound = retouchRoundRepository.findFirstByGalleryIdOrderByRoundNoDesc(galleryId)
        if (latestRound?.status == RetouchRoundStatus.COMPLETED) {
            throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
        }
        if (latestRound?.status == RetouchRoundStatus.REQUESTED) {
            if (retouchPhotoRepository.findAllByRoundId(latestRound.requiredId).any { it.hasResult }) {
                throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
            }
            latestRound.reopenRequest()
        }
        selection.withdraw()
        gallery.markSelectionInProgress()
        notificationPublisher.publish(
            userIds = galleryMemberRepository.findAllByGalleryId(galleryId).map { it.userId },
            type = UserNotificationType.SELECTION_REOPENED,
            scope = UserNotificationScope.GALLERY,
            scopeId = galleryId,
            title = "사진 선택이 다시 열렸습니다",
            message = "${gallery.title}에서 사진을 다시 선택할 수 있습니다.",
        )
        activityRecorder.recordGallery(galleryId)
        return responseOf(gallery, selection).copy(photos = emptyList())
    }

    /** V5 이전 갤러리까지 안전하게 읽기 위한 호환 경로다. 신규 갤러리는 생성 트랜잭션에서 함께 만든다. */
    private fun loadOrCreate(galleryId: Long): PhotoSelection =
        photoSelectionRepository.findByGalleryId(galleryId)
            ?: photoSelectionRepository.save(PhotoSelection(galleryId = galleryId))

    /**
     * 담을 수 있는 사진인지 확인하고 돌려준다.
     */
    private fun loadSelectablePhotos(galleryId: Long, photoIds: List<Long>): List<Photo> {
        // 빈 목록을 통과시키면 아무 일도 하지 않고 성공한다. 200을 받은 화면은 담긴 줄 안다.
        if (photoIds.isEmpty()) {
            throw SelectionException(SelectionErrorCode.EMPTY_PHOTO_IDS)
        }
        if (photoIds.size > properties.maxBatchSize) {
            throw SelectionException(SelectionErrorCode.TOO_MANY_PHOTOS)
        }

        val requested = photoIds.toSet()
        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, requested)
        if (photos.size != requested.size) {
            throw SelectionException(SelectionErrorCode.PHOTO_NOT_IN_GALLERY)
        }
        // 실체가 없는 사진이 납품 목록에 섞이면, 작가는 목록에는 있는데 열리지 않는 항목을 받는다.
        if (photos.any { it.status == PhotoStatus.PENDING }) {
            throw SelectionException(SelectionErrorCode.PHOTO_NOT_UPLOADED)
        }
        return photos
    }

    /**
     * `selection`이 null이면 아직 앨범 행이 없는 갤러리다 — 빈 앨범으로 응답한다.
     */
    private fun responseOf(gallery: Gallery, selection: PhotoSelection?): PhotoSelectionResponse {
        val items = selection?.let { photoSelectionItemRepository.findAllBySelectionId(it.requiredId) }.orEmpty()

        return PhotoSelectionResponse.of(
            selection = selection,
            maxSelectablePhotoCount = gallery.maxSelectablePhotoCount,
            photos = selectedPhotoResponses(gallery.requiredId, items),
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    /**
     * 항목을 노출 순서대로 화면 응답으로 만든다. 보정본으로 담은 항목은 결과 key를 서명해
     * 함께 싣는다 — 납품 목록이 보정본을 그리는 유일한 길이다.
     */
    private fun selectedPhotoResponses(
        galleryId: Long,
        items: List<PhotoSelectionItem>,
    ): List<SelectedPhotoResponse> {
        if (items.isEmpty()) {
            return emptyList()
        }

        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, items.map { it.photoId })
        val photoResponsesById = photoViewAssembler.toResponses(photos).associateBy { it.photoId }
        val resultKeyByRetouchPhotoId = retouchResultLoader
            .findResults(galleryId, items.mapNotNull { it.retouchPhotoId })
            .associate { it.requiredId to it.resultKey }
        return items.sortedWith(compareBy({ it.sortOrder }, { it.requiredId })).mapNotNull { item ->
            photoResponsesById[item.photoId]?.let { photoResponse ->
                val resultKey = item.retouchPhotoId?.let { resultKeyByRetouchPhotoId[it] }
                SelectedPhotoResponse(
                    itemId = item.requiredId,
                    galleryId = item.galleryId,
                    addedByUserId = item.addedByUserId,
                    sortOrder = item.sortOrder,
                    photo = photoResponse,
                    retouchPhotoId = item.retouchPhotoId,
                    resultUrl = resultKey?.let { photoStorage.presignView(it) },
                )
            }
        }
    }
    private fun requireSelectionNotExported(gallery: Gallery) {
        if (galleryAccessPolicy.isPersonalGallery(gallery.requiredId) &&
            gallery.stage in setOf(GalleryStage.RETOUCH, GalleryStage.DELIVERY, GalleryStage.ARCHIVED)
        ) throw SelectionException(SelectionErrorCode.SELECTION_ALREADY_EXPORTED)
    }
}
