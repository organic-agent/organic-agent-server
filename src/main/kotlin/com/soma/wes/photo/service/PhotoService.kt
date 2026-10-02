package com.soma.wes.photo.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.gallery.support.GalleryPhotoQuota
import com.soma.wes.global.exception.BusinessException
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoRating
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.domain.UploadState
import com.soma.wes.photo.dto.request.CheckUploadsRequest
import com.soma.wes.photo.dto.request.CompleteUploadRequest
import com.soma.wes.photo.dto.request.DeletePhotosRequest
import com.soma.wes.photo.dto.request.IssueUploadUrlsRequest
import com.soma.wes.photo.dto.request.ReissueUploadUrlsRequest
import com.soma.wes.photo.dto.response.CheckUploadsResponse
import com.soma.wes.photo.dto.response.IssueUploadUrlsResponse
import com.soma.wes.photo.dto.response.IssuedUploadResponse
import com.soma.wes.photo.dto.response.PhotoCountResponse
import com.soma.wes.photo.dto.response.PhotoDetailResponse
import com.soma.wes.photo.dto.response.PhotoPageResponse
import com.soma.wes.photo.dto.response.PhotoSummaryResponse
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.repository.PhotoSourceHashRepository
import com.soma.wes.photo.service.port.PhotoStorage
import com.soma.wes.photo.support.PhotoViewAssembler
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 원본 사진의 업로드와 조회.
 */
@Service
class PhotoService(
    private val photoRepository: PhotoRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val photoSourceHashRepository: PhotoSourceHashRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryPhotoQuota: GalleryPhotoQuota,
    private val photoStorage: PhotoStorage,
    private val photoViewAssembler: PhotoViewAssembler,
    private val properties: StorageProperties,
    private val clock: Clock,
    private val activityRecorder: ActivityRecorder,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 받아줄 이미지 형식. */
        private val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif",
        )

        private val CRC32C_BASE64 = Regex(Photo.CRC32C_BASE64_PATTERN)

        private val SOURCE_HASH = Regex(Photo.SOURCE_HASH_PATTERN)

        /** 지문 없는 파일을 "서로 다른 것"으로 세기 위한 키의 머리말. 지문 형식([Photo.SOURCE_HASH_PATTERN])과 겹칠 수 없는 글자다. */
        private const val NO_SOURCE_HASH_KEY = "#"
    }

    /**
     * 올리기 전에 원본들이 이 갤러리에 이미 있는지 답한다. web 이 폴더를 통째로 다시 던졌을 때 올릴 것만 추리는 데 쓴다 —
     * 그래서 "같은 폴더를 다시 던지는 것"이 곧 끊긴 업로드의 복구가 된다.
     *
     * 살아 있는 사진이 휴지통 사진보다 먼저다: 같은 원본이 휴지통에도 있고 다시 올라와 있기도 하면 올라온 쪽으로 답한다.
     * 읽기만 하므로 답과 발급 사이에 상태가 바뀔 수 있다 — 최종 판정은 발급([issueUploadUrls])이 갤러리를 잠그고 다시 한다.
     */
    @Transactional(readOnly = true)
    fun checkUploads(galleryId: Long, userId: Long, request: CheckUploadsRequest): CheckUploadsResponse {
        galleryAccessPolicy.requireUploader(galleryId, userId)

        val sourceHashes = request.sourceHashes.distinct()
        if (sourceHashes.size > CheckUploadsRequest.MAX_SOURCE_HASHES) {
            throw PhotoException(PhotoErrorCode.TOO_MANY_PHOTOS)
        }
        sourceHashes.forEach { validateSourceHash(it) }

        val liveByHash = photoRepository.findAllByGalleryIdAndSourceHashIn(galleryId, sourceHashes).associateBy { it.sourceHash }
        val trashed = photoSourceHashRepository.findTrashed(galleryId, sourceHashes.filterNot { it in liveByHash })
        val results = sourceHashes.map { sourceHash ->
            val live = liveByHash[sourceHash]
            CheckUploadsResponse.Result(
                sourceHash = sourceHash,
                state = when {
                    live != null -> uploadStateOf(live)
                    sourceHash in trashed -> UploadState.TRASHED
                    else -> UploadState.NEW
                },
                photoId = live?.requiredId,
            )
        }

        val countByState = results.groupingBy { it.state }.eachCount()
        log.info(
            "event=upload.check gallery={} user={} asked={} new={} pending={} uploaded={} trashed={}",
            galleryId, userId, results.size,
            countByState[UploadState.NEW] ?: 0, countByState[UploadState.PENDING] ?: 0,
            countByState[UploadState.UPLOADED] ?: 0, countByState[UploadState.TRASHED] ?: 0,
        )
        return CheckUploadsResponse(results)
    }

    private fun uploadStateOf(photo: Photo): UploadState =
        if (photo.status == PhotoStatus.UPLOADED) UploadState.UPLOADED else UploadState.PENDING

    /**
     * 업로드 1단계. 파일 목록을 받아 사진 행을 [PENDING][PhotoStatus.PENDING]으로 만들고
     * S3 PUT용 서명 URL을 돌려준다.
     *
     * 지문([Photo.sourceHash])이 있는 파일은 멱등하다. 같은 지문의 사진이 이 갤러리에 살아 있으면 행을 새로 만들지 않는다 —
     * 올리는 중이면 그 행의 URL 을 다시 주고, 이미 올라왔으면 URL 없이 그 사진을 가리킨다. 그래서 발급 호출을 재시도하거나
     * 같은 폴더를 다시 던져도 사진이 두 번 생기지 않는다. 지문이 없는 파일(옛 web)은 전처럼 파일마다 새 행이다.
     *
     * 같은 사진을 두 번 만들지 않는 근거는 갤러리 잠금이다: 잠근 뒤에 지문을 찾으므로 동시 요청 둘 중 뒤의 것은
     * 앞의 것이 만든 행을 본다. 유니크 인덱스는 이 순서가 깨졌을 때의 마지막 안전망이다.
     */
    @Transactional
    fun issueUploadUrls(
        galleryId: Long,
        userId: Long,
        request: IssueUploadUrlsRequest,
    ): IssueUploadUrlsResponse {
        galleryAccessPolicy.requireUploader(galleryId, userId)

        val existingByHash = loggingRejection(galleryId, photoCount = request.files.size) {
            validateIssueRequest(request)
            loadIssuable(galleryId, request)
        }

        // 새 행이 필요한 파일 — 지문이 없거나, 이 갤러리에 그 지문이 없다. 한 요청 안의 같은 지문은 처음 것만 행을 만든다.
        val newFiles = request.files.withIndex()
            .filter { (_, file) -> file.sourceHash == null || file.sourceHash !in existingByHash }
            .distinctBy { (index, file) -> file.sourceHash ?: "$NO_SOURCE_HASH_KEY$index" }
        photoSourceHashRepository.releaseTrashed(galleryId, newFiles.mapNotNull { (_, file) -> file.sourceHash })

        // 이미 있는 사진 뒤에 이어 붙인다. 같은 갤러리에 두 배치를 동시에 발급하면 순서가
        // 겹칠 수 있지만, 목록이 id로 한 번 더 정렬하므로 뒤섞이지는 않는다.
        val orderBase = photoRepository.nextDisplayOrder(galleryId)
        val created = photoRepository.saveAll(
            newFiles.mapIndexed { order, (_, file) ->
                Photo(
                    galleryId = galleryId,
                    storageKey = photoStorage.buildKey(galleryId, file.fileName),
                    originalFileName = file.fileName,
                    contentType = file.contentType.lowercase(),
                    displayOrder = orderBase + order,
                    sourceHash = file.sourceHash,
                )
            },
        )
        val createdByFileIndex = newFiles.map { (index, _) -> index }.zip(created).toMap()
        val createdByHash = created.filter { it.sourceHash != null }.associateBy { it.sourceHash }

        val uploads = request.files.mapIndexed { index, file ->
            val createdPhoto = createdByFileIndex[index]
            if (createdPhoto != null) {
                issue(createdPhoto, contentLength = file.contentLength, crc32c = file.crc32c, state = UploadState.NEW)
            } else {
                val photo = existingByHash[file.sourceHash] ?: createdByHash[file.sourceHash]
                    ?: error("새 행을 만들지 않은 파일은 같은 지문의 사진이 있어야 한다")
                reuse(photo, file)
            }
        }

        val resumed = uploads.count { it.state == UploadState.PENDING }
        val duplicate = uploads.count { it.state == UploadState.UPLOADED }
        if (created.isNotEmpty() || resumed > 0) activityRecorder.recordGallery(galleryId)
        log.info(
            "event=upload.issue gallery={} user={} photos={} new={} resumed={} duplicate={} bytes={}",
            galleryId, userId, uploads.size, created.size, resumed, duplicate, request.files.sumOf { it.contentLength },
        )
        return IssueUploadUrlsResponse(
            uploads = uploads,
            uploadUrlTtlSeconds = properties.uploadUrlTtl.seconds,
        )
    }

    /** 배치 크기와 형식 — DB 를 보지 않고 가릴 수 있는 것. 거절될 요청이 갤러리를 잠그지 않게 잠금보다 먼저 본다. */
    private fun validateIssueRequest(request: IssueUploadUrlsRequest) {
        if (request.files.size > properties.maxBatchSize) {
            throw PhotoException(PhotoErrorCode.TOO_MANY_PHOTOS)
        }
        request.files.forEach {
            if (it.contentType.lowercase() !in ALLOWED_CONTENT_TYPES) {
                throw PhotoException(PhotoErrorCode.UNSUPPORTED_CONTENT_TYPE)
            }
            validateContentLength(it.contentLength)
            validateCrc32c(it.crc32c)
            if (it.sourceHash != null) validateSourceHash(it.sourceHash)
        }
    }

    /**
     * 갤러리를 잠그고, 요청의 지문으로 이미 있는 사진을 찾고, 마감과 한도를 본다. 사진 행을 만들기 전에 전부 끝낸다 —
     * 거절될 요청이 PENDING 행을 남기지 않는다.
     *
     * 한도에 더할 장수는 새로 만들 행과, URL 이 죽어 한도에서 빠져 있다가 이번에 URL 을 다시 받는 행이다.
     * 이미 올라왔거나 URL 이 살아 있는 행은 벌써 세어져 있다.
     */
    private fun loadIssuable(galleryId: Long, request: IssueUploadUrlsRequest): Map<String?, Photo> {
        val gallery = galleryPhotoQuota.lock(galleryId)
        gallery.requireWritable(ZonedDateTime.now(clock))

        val sourceHashes = request.files.mapNotNull { it.sourceHash }.distinct()
        val existingByHash = photoRepository.findAllByGalleryIdAndSourceHashIn(galleryId, sourceHashes).associateBy { it.sourceHash }
        val newCount = request.files.count { it.sourceHash == null } + sourceHashes.count { it !in existingByHash }
        val revivedCount = existingByHash.values.count { it.isUploadExpired(clock.instant()) }
        galleryPhotoQuota.requireCapacity(gallery, additionalPhotoCount = newCount + revivedCount)
        return existingByHash
    }

    /** 같은 지문의 사진이 이미 있을 때. 올리는 중이면 그 행으로 URL 을 다시 주고, 올라왔으면 올릴 것이 없다고 답한다. */
    private fun reuse(photo: Photo, file: IssueUploadUrlsRequest.FileRequest): IssuedUploadResponse =
        if (photo.status == PhotoStatus.UPLOADED) {
            IssuedUploadResponse(
                photoId = photo.requiredId,
                storageKey = photo.storageKey,
                uploadUrl = null,
                state = UploadState.UPLOADED,
            )
        } else {
            issue(photo, contentLength = file.contentLength, crc32c = file.crc32c, state = UploadState.PENDING)
        }

    /** 크기는 서명에 들어가므로 여기서 상한만 보면 된다 — 다른 크기의 객체는 S3가 거절한다. */
    private fun validateContentLength(contentLength: Long) {
        if (contentLength <= 0 || contentLength > properties.maxUploadBytes) {
            throw PhotoException(PhotoErrorCode.INVALID_CONTENT_LENGTH)
        }
    }

    /** 체크섬은 서명에 그대로 들어간다. 형식만 보면 되고, 실제 바이트와 맞는지는 S3가 PUT 때 대조한다. */
    private fun validateCrc32c(crc32c: String) {
        if (!CRC32C_BASE64.matches(crc32c)) {
            throw PhotoException(PhotoErrorCode.INVALID_CHECKSUM)
        }
    }

    /** 지문은 중복 판정의 키다. 형식이 다른 값을 받아 두면 같은 원본이 다른 사진으로 갈린다. */
    private fun validateSourceHash(sourceHash: String) {
        if (!SOURCE_HASH.matches(sourceHash)) {
            throw PhotoException(PhotoErrorCode.INVALID_SOURCE_HASH)
        }
    }

    private fun issue(photo: Photo, contentLength: Long, crc32c: String, state: UploadState): IssuedUploadResponse {
        val presigned = photoStorage.presignUpload(
            key = photo.storageKey,
            contentType = photo.contentType,
            contentLength = contentLength,
            crc32c = crc32c,
        )
        photo.recordUploadUrlExpiration(presigned.expiresAt)
        return IssuedUploadResponse(
            photoId = photo.requiredId,
            storageKey = photo.storageKey,
            uploadUrl = presigned.url,
            state = state,
        )
    }

    /**
     * 끊긴 업로드의 재개. 탭을 다시 연 프론트가 자기가 기억하는 PENDING 사진 id로 새 PUT URL을 받는다 — 사진 행을 새로
     * 만들지 않으므로 같은 사진이 두 번 생기지 않는다. 이미 올라온 사진은 거절한다(새 URL로 원본을 덮어쓰게 두지 않는다).
     */
    @Transactional
    fun reissueUploadUrls(galleryId: Long, userId: Long, request: ReissueUploadUrlsRequest): IssueUploadUrlsResponse {
        galleryAccessPolicy.requireUploader(galleryId, userId)

        val photos = loggingRejection(galleryId, photoCount = request.photos.size) { loadReissuable(galleryId, request) }

        val requestById = request.photos.associateBy { it.photoId }
        val uploads = photos.map { photo ->
            val photoRequest = requestById.getValue(photo.requiredId)
            issue(photo, contentLength = photoRequest.contentLength, crc32c = photoRequest.crc32c, state = UploadState.PENDING)
        }

        if (photos.isNotEmpty()) activityRecorder.recordGallery(galleryId)
        log.info("event=upload.reissue gallery={} user={} photos={}", galleryId, userId, photos.size)
        return IssueUploadUrlsResponse(
            uploads = uploads,
            uploadUrlTtlSeconds = properties.uploadUrlTtl.seconds,
        )
    }

    /**
     * 이 갤러리의 PENDING 사진이고 새 크기·체크섬이 형식에 맞는가. URL 이 죽어 한도에서 빠져 있던 사진은 URL 을 다시 받으면
     * 다시 한도에 들므로, 그만큼 자리가 있는지도 본다 — 죽은 행을 되살리는 것으로 한도를 넘길 수 없다.
     */
    private fun loadReissuable(galleryId: Long, request: ReissueUploadUrlsRequest): List<Photo> {
        val gallery = galleryPhotoQuota.lock(galleryId)
        val photos = checkAndLoadPhotos(galleryId, request.photos.map { it.photoId })
        if (photos.any { it.status != PhotoStatus.PENDING }) {
            throw PhotoException(PhotoErrorCode.PHOTO_ALREADY_UPLOADED)
        }
        request.photos.forEach {
            validateContentLength(it.contentLength)
            validateCrc32c(it.crc32c)
        }
        galleryPhotoQuota.requireCapacity(gallery, additionalPhotoCount = photos.count { it.isUploadExpired(clock.instant()) })
        return photos
    }

    /**
     * 업로드 2단계. S3 PUT을 마친 사진들을 통보받아 [UPLOADED][PhotoStatus.UPLOADED]로 옮긴다. 여기까지 온 사진을
     * 스윕이 임베더에게 보낸다. 분석 행은 임베더가 만들므로 여기서 미리 만들지 않는다. 재통보는 멱등이다.
     * 통보가 오지 않은 사진은 [com.soma.wes.photo.support.PendingUploadSweeper]가 S3를 직접 확인해 같은 상태로 옮긴다.
     */
    @Transactional
    fun completeUpload(galleryId: Long, userId: Long, request: CompleteUploadRequest): PhotoCountResponse {
        galleryAccessPolicy.requireUploader(galleryId, userId)

        val photos = loggingRejection(galleryId, photoCount = request.photoIds.size) { checkAndLoadPhotos(galleryId, request.photoIds) }
        val now = ZonedDateTime.now(clock)
        photos.forEach { it.markUploaded(now) }
        if (photos.isNotEmpty()) activityRecorder.recordGallery(galleryId)
        log.info("event=upload.complete gallery={} user={} photos={}", galleryId, userId, photos.size)
        return PhotoCountResponse(photos.size)
    }

    /**
     * 업로드 요청의 검증·한도 거부를 한 줄 남기고 그대로 다시 던진다 — "사진이 안 올라간다"는 문의에서 이유를 찾는 줄이다.
     * 인가 실패는 감싸지 않는다. 권한 없는 호출은 업로드 문제가 아니다.
     */
    private inline fun <T> loggingRejection(galleryId: Long, photoCount: Int, block: () -> T): T = try {
        block()
    } catch (e: BusinessException) {
        log.warn("event=upload.rejected gallery={} code={} photos={}", galleryId, e.errorCode.code, photoCount)
        throw e
    }

    /**
     * 사진들을 휴지통으로 보낸다. 갤러리를 채우는 작업의 반대라 작가만 부른다.
     *
     * 여기서는 표시만 한다 — `deletedAt`이 채워지는 순간 `@SQLRestriction`이 모든 화면에서
     * 걸러낸다. 복원과 물리 삭제(재삭제·보관 만료)는 trash 도메인이 담당한다.
     *
     * [checkAndLoadPhotos]를 그대로 지나므로 전부-아니면-거부다. 이미 휴지통에 있는 사진도
     * 조회에 걸리지 않아 같은 404로 떨어진다 — 두 사람이 다른 화면에서 같은 사진을 지우면
     * 늦은 쪽 화면이 낡았다는 뜻이라, 일부만 지워진 성공처럼 보이는 것보다 낫다.
     */
    @Transactional
    fun moveToTrash(galleryId: Long, userId: Long, request: DeletePhotosRequest): PhotoCountResponse {
        galleryAccessPolicy.requireUploader(galleryId, userId)

        val photos = checkAndLoadPhotos(galleryId, request.photoIds)
        val now = ZonedDateTime.now(clock)
        photos.forEach { it.moveToTrash(now) }
        if (photos.isNotEmpty()) activityRecorder.recordGallery(galleryId)
        return PhotoCountResponse(photos.size)
    }

    /**
     * 다른 갤러리의 사진 id를 섞어 보내는 요청을 걸러낸다.
     * 갤러리 권한만 확인하고 id를 그대로 믿으면, 자기 갤러리 하나로 남의 사진 상태를 바꿀 수 있다.
     */
    private fun checkAndLoadPhotos(galleryId: Long, photoIds: List<Long>): List<Photo> {
        if (photoIds.size > properties.maxBatchSize) {
            throw PhotoException(PhotoErrorCode.TOO_MANY_PHOTOS)
        }

        val requested = photoIds.toSet()
        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, requested)
        if (photos.size != requested.size) {
            throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)
        }
        return photos
    }

    /**
     * 갤러리의 사진 목록. 작가와 부부가 함께 쓰는 전체 그리드다.
     */
    @Transactional(readOnly = true)
    fun list(
        galleryId: Long,
        userId: Long,
        status: PhotoStatus?,
        minScore: Int?,
        page: Int,
        size: Int,
    ): PhotoPageResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)
        validateMinScore(minScore)

        val studioViewer = galleryAccessPolicy.isStudioManager(galleryId, userId)
        if (studioViewer && minScore != null) throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        val found = findPage(galleryId, status, minScore, pageableOf(page, size))

        return PhotoPageResponse.of(
            page = PageResponse.of(found, photoViewAssembler.toResponses(found.content).map { if (studioViewer) it.copy(score = null) else it }),
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    private fun validateMinScore(minScore: Int?) {
        if (minScore != null && minScore !in PhotoRating.MIN_SCORE..PhotoRating.MAX_SCORE) {
            throw PhotoException(PhotoErrorCode.INVALID_SCORE)
        }
    }

    /**
     * 상태와 최소 점수는 각각 있을 수도 없을 수도 있어 네 갈래다. [list]가 쓴다.
     */
    private fun findPage(
        galleryId: Long,
        status: PhotoStatus?,
        minScore: Int?,
        pageable: PageRequest,
    ): Page<Photo> = when {
        status != null && minScore != null ->
            photoRepository.findAllByGalleryIdAndStatusAndScoreAtLeast(galleryId, status, minScore, pageable)
        status != null ->
            photoRepository.findAllByGalleryIdAndStatus(galleryId, status, pageable)
        minScore != null ->
            photoRepository.findAllByGalleryIdAndScoreAtLeast(galleryId, minScore, pageable)
        else -> photoRepository.findAllByGalleryId(galleryId, pageable)
    }

    private fun pageableOf(page: Int, size: Int): PageRequest =
        PageRequests.of(page, size, Sort.by(Sort.Direction.ASC, "displayOrder", "id"))

    /**
     * 사진 한 장의 상세. 원본을 원래 크기로 보는 화면이 부른다.
     */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, photoId: Long, userId: Long): PhotoDetailResponse {
        // 작가는 언제든, 부부는 갤러리가 열려 있고 마감 전인 동안에만 본다.
        galleryAccessPolicy.requireViewer(galleryId, userId)

        val photo = photoRepository.findByIdAndGalleryId(photoId, galleryId)
            ?: throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)

        return PhotoDetailResponse.of(
            photo = photo,
            viewUrl = photoViewAssembler.viewUrlOf(photo),
            originalUrl = photoViewAssembler.originalUrlOf(photo),
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
            originalUrlTtlSeconds = properties.originalUrlTtl.seconds,
            score = if (galleryAccessPolicy.isStudioManager(galleryId, userId)) null else photoViewAssembler.scoreOf(photo),
        )
    }

    /** 업로드·분석 진행을 한 번에 본다. 임베더·GPU 워커는 비동기라 이 집계 말고는 알 방법이 없다. */
    @Transactional(readOnly = true)
    fun summarize(galleryId: Long, userId: Long): PhotoSummaryResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        val progress = photoPipelineRepository.progressOf(
            galleryId = galleryId,
            liveSince = ZonedDateTime.now(clock).minus(properties.pendingFirstCheckAfter),
        )
        return PhotoSummaryResponse.from(progress)
    }
}
