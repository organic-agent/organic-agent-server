package com.soma.wes.retouch.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.gallery.config.GalleryLifecycleProperties
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.service.port.PhotoStorage
import com.soma.wes.retouch.domain.RetouchPhoto
import com.soma.wes.retouch.domain.RetouchRound
import com.soma.wes.retouch.domain.RetouchRoundStatus
import com.soma.wes.retouch.dto.request.AddRetouchPhotosRequest
import com.soma.wes.retouch.dto.request.CompleteResultsRequest
import com.soma.wes.retouch.dto.request.IssueResultUploadUrlsRequest
import com.soma.wes.retouch.dto.request.MatchRetouchResultsRequest
import com.soma.wes.retouch.dto.request.SubmitRetouchRequestsRequest
import com.soma.wes.retouch.dto.request.UpdateRetouchPhotoRequest
import com.soma.wes.retouch.dto.response.IssueAnnotationUploadUrlResponse
import com.soma.wes.retouch.dto.response.IssueResultUploadUrlsResponse
import com.soma.wes.retouch.dto.response.IssuedResultUploadResponse
import com.soma.wes.retouch.dto.response.MatchRetouchResultsResponse
import com.soma.wes.retouch.dto.response.RetouchOverviewResponse
import com.soma.wes.retouch.dto.response.RetouchPhotoResponse
import com.soma.wes.retouch.dto.response.RetouchRoundDetailResponse
import com.soma.wes.retouch.dto.response.RetouchRoundResponse
import com.soma.wes.retouch.dto.response.RetouchRoundSummaryResponse
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.repository.RetouchRoundRepository
import com.soma.wes.retouch.service.RetouchRequestService
import com.soma.wes.retouch.support.RetouchPhotoLoader
import com.soma.wes.retouch.support.RetouchViewAssembler
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.trash.service.ProductChildTrashService
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import java.time.Clock
import java.time.ZonedDateTime
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * 셀렉 확정 전에 부부가 보정을 요청하는 흐름. 보정사진을 모아 회차 단위로 일괄 제출한다.
 */
@Service
class RetouchService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val retouchRoundRepository: RetouchRoundRepository,
    private val retouchPhotoRepository: RetouchPhotoRepository,
    private val retouchPhotoLoader: RetouchPhotoLoader,
    private val retouchViewAssembler: RetouchViewAssembler,
    private val productChildTrashService: ProductChildTrashService,
    private val photoStorage: PhotoStorage,
    private val properties: StorageProperties,
    private val clock: Clock,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val notificationPublisher: UserNotificationPublisher,
    private val requestWriter: RetouchRequestService,
    private val selectionRepository: PhotoSelectionRepository,
    private val selectionItemRepository: PhotoSelectionItemRepository,
    private val photoRepository: PhotoRepository,
    private val transactionTemplate: TransactionTemplate,
    private val lifecycleProperties: GalleryLifecycleProperties,
    private val activityRecorder: ActivityRecorder,
) {

    companion object {

        /** 주석은 프론트 캔버스가 내보내는 투명 배경 레이어라 형식이 PNG 하나로 고정된다. */
        private const val ANNOTATION_CONTENT_TYPE = "image/png"

        /**
         * 결과로 받아줄 이미지 형식과 key에 붙일 확장자. 원본 업로드가 받는 형식과 같은
         * 집합인데, 원본은 파일명에서 확장자를 얻지만 결과는 파일명을 저장하지 않아
         * Content-Type에서 얻는다 — 그래서 목록이 아니라 매핑이다.
         */
        private val RESULT_EXTENSIONS_BY_CONTENT_TYPE = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/heic" to "heic",
            "image/heif" to "heif",
        )
    }

    /** 갤러리 키 공간([PhotoStorage.galleryPrefix]) 아래의 고정 자리. */
    private fun annotationKeyPrefix(galleryId: Long): String =
        "${photoStorage.galleryPrefix(galleryId)}retouch/annotations/"

    private fun resultKeyPrefix(galleryId: Long, roundNo: Int): String =
        "${photoStorage.galleryPrefix(galleryId)}retouch/results/$roundNo/"

    /**
     * 보정사진 페이지를 연다. 작가는 요청을 봐야 하고, 부부는 마감 뒤에도 결과를 봐야 하므로
     * 조회는 Viewer 문이다.
     */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): RetouchOverviewResponse {
        val gallery = galleryAccessPolicy.requireViewer(galleryId, userId)

        return overviewOf(gallery, userId)
    }

    /**
     * 보정사진을 담는다. 진행 중인 DRAFTING 회차가 없으면 첫 담기 때 만들어진다.
     */
    @Transactional
    fun addPhotos(galleryId: Long, userId: Long, request: AddRetouchPhotosRequest): RetouchOverviewResponse {
        galleryAccessPolicy.requireRetouchRequester(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        val round = loadOrCreateDraftingRound(gallery)

        val photos = retouchPhotoLoader.loadPhotos(galleryId, request.photoIds)
        retouchPhotoLoader.validateNoneInRound(round.requiredId, request.photoIds)

        retouchPhotoRepository.saveAll(
            photos.map {
                RetouchPhoto(roundId = round.requiredId, galleryId = galleryId, photoId = it.requiredId)
            },
        )

        activityRecorder.recordGallery(galleryId)
        return overviewOf(gallery, userId)
    }

    /**
     * 첫 담기 때 DRAFTING 회차가 만들어진다. 갤러리 행이 잠겨 있어 두 요청이 겹치지 않는다.
     * 이전 회차가 끝나기 전에는 새 회차를 열지 않는다 — 회차는 갤러리당 하나씩만 진행된다.
     */
    private fun loadOrCreateDraftingRound(gallery: Gallery): RetouchRound {
        val galleryId = gallery.requiredId
        retouchRoundRepository.findByGalleryIdAndStatus(galleryId, RetouchRoundStatus.DRAFTING)
            ?.let { return it }

        val latest = retouchRoundRepository.findFirstByGalleryIdOrderByRoundNoDesc(galleryId)
        if (latest != null && latest.status == RetouchRoundStatus.REQUESTED) {
            throw RetouchException(RetouchErrorCode.ROUND_IN_PROGRESS)
        }
        // 여기 오면 기존 회차는 전부 COMPLETED다 — 어차피 제출하지 못할 회차에 사진을 모으게
        // 두지 않는다. 최종 관문은 제출의 같은 검사다.
        validateWithinMaxRounds(gallery, submittedRoundCount = latest?.roundNo ?: 0)

        return retouchRoundRepository.save(
            RetouchRound(
                galleryId = galleryId,
                roundNo = latest?.roundNo?.plus(1) ?: RetouchRound.FIRST_ROUND_NO,
            ),
        )
    }

    private fun validateWithinMaxRounds(gallery: Gallery, submittedRoundCount: Int) {
        val max = gallery.maxRetouchRoundCount
        if (max != null && submittedRoundCount >= max) {
            throw RetouchException(RetouchErrorCode.MAX_RETOUCH_ROUND_COUNT_EXCEEDED)
        }
    }

    /**
     * DRAFTING 회차에서 한 장을 빼낸다. 없으면 404다.
     */
    @Transactional
    fun removePhoto(galleryId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requireRetouchRequester(galleryId, userId)

        galleryRepository.requireWithLockById(galleryId)
        val round = requireDraftingRound(galleryId, RetouchErrorCode.PHOTO_NOT_IN_ROUND)

        if (!productChildTrashService.removeUserRetouchItem(round.requiredId, photoId)) {
            throw RetouchException(RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        }
        activityRecorder.recordGallery(galleryId)
    }

    /**
     * 사진 한 장의 요청 텍스트와 주석 key를 저장한다. DRAFTING 동안에만, 덮어쓰기로 동작한다 —
     * 제출 뒤에는 DRAFTING 회차가 없어 404다.
     */
    @Transactional
    fun updatePhoto(
        galleryId: Long,
        photoId: Long,
        userId: Long,
        request: UpdateRetouchPhotoRequest,
    ): RetouchPhotoResponse {
        galleryAccessPolicy.requireRetouchRequester(galleryId, userId)

        // 항목 하나의 갱신이지만 갤러리 행을 잠근다 — 제출과 겹치면 잠긴 회차에 요청이 적힌다.
        galleryRepository.requireWithLockById(galleryId)
        val round = requireDraftingRound(galleryId, RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        val item = retouchPhotoRepository.findByRoundIdAndPhotoId(round.requiredId, photoId)
            ?: throw RetouchException(RetouchErrorCode.PHOTO_NOT_IN_ROUND)

        validateAnnotationKey(galleryId, request.annotationKey)
        item.writeRequest(request.requestText, request.annotationKey, request.points)
        activityRecorder.recordGallery(galleryId)

        return retouchViewAssembler.toResponses(galleryId, listOf(item)).first()
    }

    private fun validateAnnotationKey(galleryId: Long, annotationKey: String?) {
        if (annotationKey != null && !annotationKey.startsWith(annotationKeyPrefix(galleryId))) {
            throw RetouchException(RetouchErrorCode.INVALID_ANNOTATION_KEY)
        }
    }

    /**
     * 주석 이미지가 올라갈 자리의 서명 URL을 발급한다. 사진과의 연결은 발급이 아니라
     * 요청 저장([updatePhoto])이 만든다 — 그래서 발급은 아무 행도 만들지 않는다.
     */
    @Transactional(readOnly = true)
    fun issueAnnotationUploadUrl(galleryId: Long, userId: Long): IssueAnnotationUploadUrlResponse {
        galleryAccessPolicy.requireRetouchRequester(galleryId, userId)

        val key = "${annotationKeyPrefix(galleryId)}${UUID.randomUUID()}.png"
        val presigned = photoStorage.presignUpload(key, ANNOTATION_CONTENT_TYPE)

        return IssueAnnotationUploadUrlResponse(
            annotationKey = key,
            uploadUrl = presigned.url,
            uploadUrlTtlSeconds = properties.uploadUrlTtl.seconds,
        )
    }

    /**
     * 회차를 제출한다. 이 시점의 요청들이 한 회차가 되고, 계약 횟수 한 번을 쓴다.
     */
    @Transactional
    fun submitRound(galleryId: Long, userId: Long): RetouchOverviewResponse {
        galleryAccessPolicy.requireRetouchRequester(galleryId, userId)
        requireStudioRoundAction(galleryId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        val round = requireDraftingRound(galleryId, RetouchErrorCode.EMPTY_ROUND)
        if (retouchPhotoRepository.countByRoundId(round.requiredId) == 0L) {
            throw RetouchException(RetouchErrorCode.EMPTY_ROUND)
        }

        // 회차를 만든 뒤 계약 횟수가 줄었을 수 있어 제출이 최종 관문이다.
        val submittedCount = retouchRoundRepository
            .countByGalleryIdAndStatusNot(galleryId, RetouchRoundStatus.DRAFTING)
        validateWithinMaxRounds(gallery, submittedRoundCount = submittedCount.toInt())

        round.submit(ZonedDateTime.now(clock))
        gallery.markRetouchStarted()
        notificationPublisher.publish(
            userIds = workspaceMemberRepository.findAllByWorkspaceId(gallery.workspaceId)
                .map { it.userId },
            type = UserNotificationType.RETOUCH_REQUESTED,
            scope = UserNotificationScope.GALLERY,
            scopeId = galleryId,
            title = "보정 요청이 도착했습니다",
            message = "${gallery.title}의 보정 요청이 제출되었습니다.",
        )
        activityRecorder.recordGallery(galleryId)
        return overviewOf(gallery, userId)
    }

    /** DRAFTING 회차가 없다는 것을 무엇으로 알릴지는 유스케이스마다 다르다 — 호출자가 정한다. */
    private fun requireDraftingRound(galleryId: Long, errorCode: RetouchErrorCode): RetouchRound =
        retouchRoundRepository.findByGalleryIdAndStatus(galleryId, RetouchRoundStatus.DRAFTING)
            ?: throw RetouchException(errorCode)

    /**
     * 회차 상세를 연다. 항목마다 원본과 결과 URL을 나란히 줘 전/후 비교가 된다.
     * 부부는 마감 뒤에도 결과를 봐야 하므로 조회는 Viewer 문이다.
     */
    @Transactional(readOnly = true)
    fun getRound(galleryId: Long, roundNo: Int, userId: Long): RetouchRoundDetailResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        val round = retouchRoundRepository.findByGalleryIdAndRoundNo(galleryId, roundNo)
            ?: throw RetouchException(RetouchErrorCode.ROUND_NOT_FOUND)
        val items = if (round.isDrafting && galleryAccessPolicy.isStudioManager(galleryId, userId)) emptyList()
            else retouchPhotoRepository.findAllByRoundId(round.requiredId)

        val hideRating = galleryAccessPolicy.isStudioManager(galleryId, userId)
        return RetouchRoundDetailResponse.of(
            round = round,
            photos = retouchViewAssembler.toDetailResponses(
                galleryId, items,
                includeResults = round.status == RetouchRoundStatus.COMPLETED ||
                    galleryAccessPolicy.canInspectRetouchDrafts(galleryId, userId),
            ).map { if (hideRating) it.copy(photo = it.photo.copy(score = null)) else it },
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    /**
     * 결과 파일들이 올라갈 자리의 서명 URL을 발급한다. 주석과 같은 방식이라 발급은 아무 행도
     * 만들지 않는다 — 항목과의 연결은 결과 확정([completeResults])이 만든다.
     */
    @Transactional
    fun issueResultUploadUrls(
        galleryId: Long,
        roundNo: Int,
        userId: Long,
        request: IssueResultUploadUrlsRequest,
    ): IssueResultUploadUrlsResponse {
        val authorizedGallery = galleryAccessPolicy.requireRetouchProcessor(galleryId, userId)
        requirePersonalResultRound(authorizedGallery, roundNo)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        val round = findRequestedRound(galleryId, roundNo)
        loadItemsInRound(round.requiredId, request.files.map { it.photoId })

        val uploads = request.files.map { file ->
            val key = "${resultKeyPrefix(galleryId, roundNo)}${UUID.randomUUID()}.${resultExtensionOf(file.contentType)}"
            IssuedResultUploadResponse(
                photoId = file.photoId,
                resultKey = key,
                uploadUrl = photoStorage.presignUpload(key, file.contentType).url,
            )
        }

        gallery.markRetouchStarted()
        activityRecorder.recordGallery(galleryId)
        return IssueResultUploadUrlsResponse(
            uploads = uploads,
            uploadUrlTtlSeconds = properties.uploadUrlTtl.seconds,
        )
    }

    private fun resultExtensionOf(contentType: String): String =
        RESULT_EXTENSIONS_BY_CONTENT_TYPE[contentType.lowercase()]
            ?: throw RetouchException(RetouchErrorCode.UNSUPPORTED_CONTENT_TYPE)

    /**
     * S3 PUT을 마친 결과들을 항목에 기록한다. 회차가 끝나기 전에는 다시 올린 key로 덮어쓴다.
     */
    fun completeResults(
        galleryId: Long,
        roundNo: Int,
        userId: Long,
        request: CompleteResultsRequest,
    ): RetouchRoundDetailResponse {
        val authorizedGallery = galleryAccessPolicy.requireRetouchProcessor(galleryId, userId)
        requirePersonalResultRound(authorizedGallery, roundNo)
        val round = findRequestedRound(galleryId, roundNo)
        loadItemsInRound(round.requiredId, request.results.map { it.photoId })
        if (request.results.map { it.resultKey }.toSet().size != request.results.size) {
            throw RetouchException(RetouchErrorCode.DUPLICATE_RESULT)
        }
        request.results.forEach { result ->
            validateResultKey(galleryId, roundNo, result.resultKey)
            resultExtensionOf(result.contentType)
        }

        // S3 응답을 기다리는 동안 DB 연결이나 갤러리 잠금을 잡지 않는다.
        if (request.results.any { !photoStorage.exists(it.resultKey) }) {
            throw RetouchException(RetouchErrorCode.RESULT_UPLOAD_INCOMPLETE)
        }

        return transactionTemplate.execute {
            galleryAccessPolicy.requireRetouchProcessor(galleryId, userId)
            val gallery = galleryRepository.requireWithLockById(galleryId)
            gallery.requireWritable(ZonedDateTime.now(clock))
            requirePersonalResultRound(gallery, roundNo)
            val lockedRound = lockRequestedRound(galleryId, roundNo)
            val items = loadItemsInRound(lockedRound.requiredId, request.results.map { it.photoId })
            val existingResults = retouchPhotoRepository.findAllByRoundId(lockedRound.requiredId)
                .filter { it.hasResult }.associate { it.resultKey to it.photoId }
            if (request.results.any { result -> existingResults[result.resultKey]?.let { it != result.photoId } == true }) {
                throw RetouchException(RetouchErrorCode.DUPLICATE_RESULT)
            }
            val itemsByPhotoId = items.associateBy { it.photoId }
            request.results.forEach { result ->
                itemsByPhotoId.getValue(result.photoId).writeResult(result.resultKey, result.contentType.lowercase())
            }
            activityRecorder.recordGallery(galleryId)
            val hideRating = galleryAccessPolicy.isStudioManager(galleryId, userId)
            RetouchRoundDetailResponse.of(
                round = lockedRound,
                photos = retouchViewAssembler.toDetailResponses(
                    galleryId, retouchPhotoRepository.findAllByRoundId(lockedRound.requiredId),
                ).map { if (hideRating) it.copy(photo = it.photo.copy(score = null)) else it },
                viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
            )
        } ?: throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
    }

    private fun validateResultKey(galleryId: Long, roundNo: Int, resultKey: String) {
        if (!resultKey.startsWith(resultKeyPrefix(galleryId, roundNo))) {
            throw RetouchException(RetouchErrorCode.INVALID_RESULT_KEY)
        }
    }

    /** 요청 목록에 이 회차에 없는 사진이 섞이면 전체를 거절하고, 있으면 그 항목들을 돌려준다. */
    private fun loadItemsInRound(roundId: Long, photoIds: List<Long>): List<RetouchPhoto> {
        if (photoIds.isEmpty()) {
            throw RetouchException(RetouchErrorCode.EMPTY_PHOTO_IDS)
        }
        if (photoIds.size > properties.maxBatchSize) {
            throw RetouchException(RetouchErrorCode.TOO_MANY_PHOTOS)
        }

        val cleanPhotoIds = photoIds.toSet()
        if (cleanPhotoIds.size != photoIds.size) throw RetouchException(RetouchErrorCode.DUPLICATE_RESULT)
        val found = retouchPhotoRepository.findAllByRoundIdAndPhotoIdIn(roundId, cleanPhotoIds)
        if (found.size != cleanPhotoIds.size) {
            throw RetouchException(RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        }
        return found
    }

    /**
     * 회차를 끝낸다. 요청 전부에 응답했을 때만 끝낼 수 있고, 이때부터 부부가 다음 회차를
     * 시작할 수 있다.
     */
    @Transactional
    fun completeRound(galleryId: Long, roundNo: Int, userId: Long): RetouchOverviewResponse {
        val gallery = galleryAccessPolicy.requireRetouchProcessor(galleryId, userId)
        requireStudioRoundAction(galleryId)

        galleryRepository.requireWithLockById(galleryId)
        val round = lockRequestedRound(galleryId, roundNo)
        val items = retouchPhotoRepository.findAllByRoundId(round.requiredId)
        if (items.isEmpty() || items.any { !it.hasResult }) {
            throw RetouchException(RetouchErrorCode.MISSING_RESULT)
        }

        round.complete(ZonedDateTime.now(clock))
        gallery.markDeliveryReady()
        notificationPublisher.publish(
            userIds = galleryMemberRepository.findAllByGalleryId(galleryId).map { it.userId },
            type = UserNotificationType.RETOUCH_COMPLETED,
            scope = UserNotificationScope.GALLERY,
            scopeId = galleryId,
            title = "보정 결과가 준비되었습니다",
            message = "${gallery.title}의 보정 결과를 확인할 수 있습니다.",
        )
        activityRecorder.recordGallery(galleryId)
        return overviewOf(gallery, userId)
    }

    /**
     * 결과를 쓰는 경로는 회차 행을 잠근다 — 확정과 완료가 겹치면 끝난 회차에 결과가 적힌다.
     * 작가의 차례(REQUESTED)가 아닌 회차에는 결과를 쓸 수 없다.
     */
    private fun lockRequestedRound(galleryId: Long, roundNo: Int): RetouchRound {
        val round = retouchRoundRepository.findWithLockByGalleryIdAndRoundNo(galleryId, roundNo)
            ?: throw RetouchException(RetouchErrorCode.ROUND_NOT_FOUND)
        return round.also { validateRequested(it) }
    }

    private fun findRequestedRound(galleryId: Long, roundNo: Int): RetouchRound {
        val round = retouchRoundRepository.findByGalleryIdAndRoundNo(galleryId, roundNo)
            ?: throw RetouchException(RetouchErrorCode.ROUND_NOT_FOUND)
        return round.also { validateRequested(it) }
    }

    private fun validateRequested(round: RetouchRound) {
        if (round.status != RetouchRoundStatus.REQUESTED) {
            throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
        }
    }

    /** N차 요청은 제출된 선택 안에서만 만들며, 1차 기본 보정은 선택 제출과 함께 생성한다. */
    @Transactional
    fun submitRequests(
        galleryId: Long,
        roundNo: Int,
        userId: Long,
        request: SubmitRetouchRequestsRequest,
    ): RetouchOverviewResponse {
        galleryAccessPolicy.requireRetouchRequester(galleryId, userId)
        requireStudioRoundAction(galleryId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        val selection = selectionRepository.findByGalleryId(galleryId)
            ?: throw RetouchException(RetouchErrorCode.PHOTO_NOT_SELECTED)
        if (!selection.isSubmitted && !galleryAccessPolicy.isPersonalGallery(galleryId)) {
            throw RetouchException(RetouchErrorCode.PHOTO_NOT_SELECTED)
        }
        val selectedIds = selectionItemRepository.findAllBySelectionId(selection.requiredId).map { it.photoId }
        val targets = if (request.requests.isEmpty()) selectedIds else request.requests.map { it.photoId }
        if (targets.any { it !in selectedIds }) throw RetouchException(RetouchErrorCode.PHOTO_NOT_SELECTED)
        requestWriter.submit(gallery, roundNo, targets, request.requests, ZonedDateTime.now(clock))
        notificationPublisher.publish(
            userIds = workspaceMemberRepository.findAllByWorkspaceId(gallery.workspaceId).map { it.userId },
            type = UserNotificationType.RETOUCH_REQUESTED,
            scope = UserNotificationScope.GALLERY,
            scopeId = galleryId,
            title = "보정 요청이 도착했습니다",
            message = "${gallery.title}의 ${roundNo}차 보정 요청이 제출되었습니다.",
        )
        activityRecorder.recordGallery(galleryId)
        return overviewOf(gallery, userId)
    }

    /** 파일명은 확장자를 제외하고 비교한다. 중복 이름은 자동으로 고르지 않고 후보를 반환한다. */
    @Transactional(readOnly = true)
    fun matchResults(
        galleryId: Long,
        roundNo: Int,
        userId: Long,
        request: MatchRetouchResultsRequest,
    ): MatchRetouchResultsResponse {
        val authorizedGallery = galleryAccessPolicy.requireRetouchProcessor(galleryId, userId)
        requirePersonalResultRound(authorizedGallery, roundNo)
        val round = findRequestedRound(galleryId, roundNo)
        val items = retouchPhotoRepository.findAllByRoundId(round.requiredId)
        if (request.files.isEmpty()) throw RetouchException(RetouchErrorCode.EMPTY_PHOTO_IDS)
        if (request.files.size > properties.maxBatchSize) throw RetouchException(RetouchErrorCode.TOO_MANY_PHOTOS)
        val candidates = photoRepository.findAllByGalleryIdAndIdIn(galleryId, items.map { it.photoId })
            .sortedWith(com.soma.wes.photo.domain.Photo.DISPLAY_ORDER)
            .map { MatchRetouchResultsResponse.Candidate(photoId = it.requiredId, filename = it.originalFileName) }
        val matches = request.files.map { file ->
            if (file.filename.isBlank() || file.filename.length > 255 || file.filename.contains('/') || file.filename.contains('\\')) {
                throw RetouchException(RetouchErrorCode.INVALID_FILENAME)
            }
            resultExtensionOf(file.contentType)
            val stem = file.filename.substringBeforeLast('.').lowercase()
            val matched = candidates.filter { it.filename.substringBeforeLast('.').lowercase() == stem }
            MatchRetouchResultsResponse.Match(
                filename = file.filename,
                contentType = file.contentType,
                photoId = matched.singleOrNull()?.photoId,
                candidates = if (matched.isEmpty()) candidates else matched,
            )
        }
        return MatchRetouchResultsResponse(matches = matches)
    }

    /** 보정본을 받은 클라이언트가 최종 확정하면 읽기 전용 보관 단계로 끝난다. */
    @Transactional
    fun confirm(galleryId: Long, userId: Long): RetouchOverviewResponse {
        galleryAccessPolicy.requireRetouchRequester(galleryId, userId)
        requireStudioRoundAction(galleryId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        val latest = retouchRoundRepository.findFirstByGalleryIdOrderByRoundNoDesc(galleryId)
            ?: throw RetouchException(RetouchErrorCode.ROUND_NOT_FOUND)
        if (latest.status != RetouchRoundStatus.COMPLETED) {
            throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
        }
        val now = ZonedDateTime.now(clock)
        gallery.markRetouchConfirmed(now)
        if (gallery.archivedUntil == null) lifecycleProperties.archivedRetentionDays?.let {
            gallery.archivedUntil = now.plusDays(it.toLong())
        }
        notificationPublisher.publish(
            userIds = workspaceMemberRepository.findAllByWorkspaceId(gallery.workspaceId).map { it.userId },
            type = UserNotificationType.RETOUCH_CONFIRMED,
            scope = UserNotificationScope.GALLERY,
            scopeId = galleryId,
            title = "보정이 확정되었습니다",
            message = "${gallery.title}의 보정이 최종 확정되었습니다.",
        )
        activityRecorder.recordGallery(galleryId)
        return overviewOf(gallery, userId)
    }

    private fun requireStudioRoundAction(galleryId: Long) {
        if (galleryAccessPolicy.isPersonalGallery(galleryId)) {
            throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
        }
    }

    private fun requirePersonalResultRound(gallery: Gallery, roundNo: Int) {
        if (galleryAccessPolicy.isPersonalGallery(gallery.requiredId) &&
            (roundNo != RetouchRound.FIRST_ROUND_NO || gallery.stage != GalleryStage.RETOUCH)
        ) throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
    }

    private fun overviewOf(gallery: Gallery, userId: Long): RetouchOverviewResponse {
        val galleryId = gallery.requiredId
        val rounds = retouchRoundRepository.findAllByGalleryIdOrderByRoundNoAsc(galleryId)
        val photoCounts = retouchPhotoRepository.countAllByGalleryIdGroupByRoundId(galleryId)
            .associate { it.roundId to it.photoCount }

        val hideRating = galleryAccessPolicy.isStudioManager(galleryId, userId)
        val inspectDrafts = galleryAccessPolicy.canInspectRetouchDrafts(galleryId, userId)
        val currentRound = rounds.lastOrNull { it.status != RetouchRoundStatus.COMPLETED }
        val currentItems = currentRound
            ?.takeUnless { it.isDrafting && hideRating }
            ?.let { retouchPhotoRepository.findAllByRoundId(it.requiredId) }
            .orEmpty()

        return RetouchOverviewResponse.of(
            maxRetouchRoundCount = gallery.maxRetouchRoundCount,
            submittedRoundCount = rounds.count { it.status != RetouchRoundStatus.DRAFTING },
            rounds = rounds.map { RetouchRoundSummaryResponse.of(it, photoCounts[it.requiredId] ?: 0L) },
            currentRound = currentRound?.let {
                RetouchRoundResponse.of(it, retouchViewAssembler.toResponses(galleryId, currentItems).map { photo ->
                    photo.copy(
                        hasResult = photo.hasResult && (it.status == RetouchRoundStatus.COMPLETED || inspectDrafts),
                        photo = if (hideRating) photo.photo.copy(score = null) else photo.photo,
                    )
                })
            },
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }
}
