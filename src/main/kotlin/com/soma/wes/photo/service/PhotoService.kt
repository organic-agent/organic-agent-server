package com.soma.wes.photo.service

import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoRating
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.CompleteUploadRequest
import com.soma.wes.photo.dto.request.IssueUploadUrlsRequest
import com.soma.wes.photo.dto.response.IssueUploadUrlsResponse
import com.soma.wes.photo.dto.response.IssuedUploadResponse
import com.soma.wes.photo.dto.response.PhotoCountResponse
import com.soma.wes.photo.dto.response.PhotoDetailResponse
import com.soma.wes.photo.dto.response.PhotoPageResponse
import com.soma.wes.photo.dto.response.PhotoSummaryResponse
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import com.soma.wes.studio.support.StudioWriteAdmission
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 원본 사진의 업로드와 조회.
 *
 * 이미지 바이트는 이 서버를 거치지 않는다. 서버는 목적지(`storage_key`)를 정해 서명 URL을 주고,
 * 프론트가 S3에 직접 올린 뒤 완료를 통보한다. 수천 장 원본이 서버 메모리를 지나가면
 * 1GB 컨테이너가 버티지 못한다.
 *
 * 사진을 올리고 완료를 통보하고 집계를 보는 경로는 [GalleryAccessPolicy.requirePhotographer]를
 * 지난다. 그것은 작가가 갤러리를 채우는 작업이라 부부가 볼 화면이 아니다.
 *
 * 반면 **읽는 경로는 부부에게도 열려 있다** — [목록][list]과 [상세][get] 둘이다. 전체를 훑고
 * 마음에 드는 것을 고르는 것이 부부가 하는 일이므로, 그 전체를 열지 않으면 부부는 비슷한 사진
 * 묶음(클러스터·폴더)으로만 사진을 만나게 된다.
 */
@Service
class PhotoService(
    private val photoRepository: PhotoRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoStorage: PhotoStorage,
    private val photoViewAssembler: PhotoViewAssembler,
    private val properties: StorageProperties,
    private val studioWriteAdmission: StudioWriteAdmission,
) {

    companion object {
        /**
         * 받아줄 이미지 형식.
         *
         * 임베딩 Lambda가 디코딩할 수 있는 것으로 제한한다. 여기서 막지 않으면 업로드는 전부
         * 성공하고 임베딩만 조용히 실패해, 사진이 영영 UPLOADED에 머무는 것으로만 드러난다.
         * HEIC/HEIF는 아이폰 기본 형식이라 반드시 포함한다.
         */
        private val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif",
        )
    }

    /**
     * 업로드 1단계. 파일 목록을 받아 사진 행을 [PENDING][PhotoStatus.PENDING]으로 만들고
     * S3 PUT용 서명 URL을 돌려준다.
     *
     * 수천 장을 한 요청으로 받는다. 장당 요청을 보내면 왕복 지연만으로 업로드가 시작되기 전에
     * 몇 분이 지나간다.
     */
    @Transactional
    fun issueUploadUrls(
        galleryId: Long,
        userId: Long,
        request: IssueUploadUrlsRequest,
    ): IssueUploadUrlsResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)
        studioWriteAdmission.requireWritable(gallery.studioId)

        if (request.files.size > properties.maxBatchSize) {
            throw PhotoException(PhotoErrorCode.TOO_MANY_PHOTOS)
        }
        request.files.forEach {
            if (it.contentType.lowercase() !in ALLOWED_CONTENT_TYPES) {
                throw PhotoException(PhotoErrorCode.UNSUPPORTED_CONTENT_TYPE)
            }
        }

        // 이미 있는 사진 뒤에 이어 붙인다. 같은 갤러리에 두 배치를 동시에 발급하면 순서가
        // 겹칠 수 있지만, 목록이 id로 한 번 더 정렬하므로 뒤섞이지는 않는다.
        val orderBase = photoRepository.countByGalleryId(galleryId).toInt()

        val photos = request.files.mapIndexed { index, file ->
            Photo(
                galleryId = galleryId,
                storageKey = photoStorage.buildKey(galleryId, file.fileName),
                originalFileName = file.fileName,
                contentType = file.contentType.lowercase(),
                displayOrder = orderBase + index,
            )
        }

        val uploads = photoRepository.saveAll(photos).map { photo ->
            val presigned = photoStorage.presignUpload(photo.storageKey, photo.contentType)
            photo.recordUploadUrlExpiration(presigned.expiresAt)
            IssuedUploadResponse(
                photoId = photo.requiredId,
                storageKey = photo.storageKey,
                uploadUrl = presigned.url,
            )
        }

        return IssueUploadUrlsResponse(
            uploads = uploads,
            uploadUrlTtlSeconds = properties.uploadUrlTtl.seconds,
        )
    }

    /**
     * 업로드 2단계. S3 PUT을 마친 사진들을 통보받아 [UPLOADED][PhotoStatus.UPLOADED]로 옮긴다.
     * 여기까지 온 사진만 임베딩 대상이 된다.
     */
    @Transactional
    fun completeUpload(galleryId: Long, userId: Long, request: CompleteUploadRequest): PhotoCountResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val photos = loadPhotosIn(galleryId, request.photoIds)
        photos.forEach { it.markUploaded() }
        return PhotoCountResponse(photos.size)
    }

    /**
     * 다른 갤러리의 사진 id를 섞어 보내는 요청을 걸러낸다.
     *
     * 갤러리 권한만 확인하고 id를 그대로 믿으면, 자기 갤러리 하나로 남의 사진 상태를 바꿀 수 있다.
     */
    private fun loadPhotosIn(galleryId: Long, photoIds: List<Long>): List<Photo> {
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
     *
     * 사진마다 서명된 조회 URL과 별점이 붙어 온다. 버킷이 비공개라 `storageKey`만으로는 아무것도
     * 띄울 수 없어서, 그 URL이 이미지를 화면에 그리는 유일한 통로다.
     *
     * `minScore`를 주면 그 점수 이상만 온다 — "4점 이상만 보기"다. 별점이 아예 없는 사진은
     * 이때 빠진다.
     *
     * [GalleryAccessPolicy.requirePhotographerOrCouple]이 아니라
     * [GalleryAccessPolicy.requireViewer]인 것이 중요하다. 목록은 고르는 동작이 아니라 보는
     * 동작이라, 마감된 뒤에 자기 갤러리를 열었을 때 사진이 통째로 사라지면 안 된다 — 갤러리
     * 상세와 선택 앨범 조회가 같은 기준을 쓴다. 아직 열리지 않은(DRAFT) 갤러리가 부부에게
     * 보이지 않는 것은 그 정책이 그대로 막아준다.
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

        if (size !in 1..properties.maxBatchSize) {
            throw PhotoException(PhotoErrorCode.TOO_MANY_PHOTOS)
        }
        // 컨트롤러의 @Min·@Max가 먼저 걸러내지만 그 검증은 컨트롤러를 지날 때만 돈다.
        // 범위를 벗어난 값은 조용히 빈 목록이 되어, 화면에는 "고른 사진이 없다"로 보인다.
        if (minScore != null && minScore !in PhotoRating.MIN_SCORE..PhotoRating.MAX_SCORE) {
            throw PhotoException(PhotoErrorCode.INVALID_SCORE)
        }

        // displayOrder가 같은 사진(같은 배치에 동시 발급된 것들)이 페이지를 넘길 때마다
        // 자리를 바꾸지 않도록 id로 한 번 더 정렬한다.
        val pageable = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.ASC, "displayOrder", "id"),
        )
        val found = findPage(galleryId, status, minScore, pageable)

        return PhotoPageResponse(
            photos = photoViewAssembler.toResponses(found.content),
            page = found.number,
            size = found.size,
            totalCount = found.totalElements,
            hasNext = found.hasNext(),
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    /**
     * 상태와 최소 점수는 각각 있을 수도 없을 수도 있어 네 갈래다. [list]가 쓴다.
     *
     * 하나의 질의에 `(:status IS NULL OR ...)`을 넣지 않는다 — 그렇게 쓰면 어느 조건도 안 걸린
     * 흔한 경우까지 옵티마이저가 매번 다시 판단해야 하고, 파라미터가 null일 때의 타입 추론이
     * enum에서 어긋난다.
     */
    private fun findPage(
        galleryId: Long,
        status: PhotoStatus?,
        minScore: Int?,
        pageable: PageRequest,
    ): Page<Photo> = when {
        status != null && minScore != null ->
            photoRepository.findAllByGalleryIdAndStatusAndScoreAtLeast(galleryId, status, minScore, pageable)

        status != null -> photoRepository.findAllByGalleryIdAndStatus(galleryId, status, pageable)
        minScore != null -> photoRepository.findAllByGalleryIdAndScoreAtLeast(galleryId, minScore, pageable)
        else -> photoRepository.findAllByGalleryId(galleryId, pageable)
    }

    /**
     * 사진 한 장의 상세. 원본을 원래 크기로 보는 화면이 부른다.
     *
     * 목록과 달리 조회 URL을 둘 준다. 파생본([PhotoViewAssembler.viewUrlOf])은 브라우저가
     * 확실히 그리지만 긴 변을 줄인 JPEG라 확대하면 뭉개지고, 원본은 원래 크기지만 HEIC면
     * 아무것도 그려지지 않는다. 어느 쪽을 쓸지는 화면이 정한다.
     */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, photoId: Long, userId: Long): PhotoDetailResponse {
        // 작가는 언제든, 부부는 갤러리가 열려 있고 마감 전인 동안에만 본다.
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val photo = photoRepository.findByIdAndGalleryId(photoId, galleryId)
            ?: throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)

        return PhotoDetailResponse.of(
            photo = photo,
            viewUrl = photoViewAssembler.viewUrlOf(photo),
            originalUrl = photoViewAssembler.originalUrlOf(photo),
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
            originalUrlTtlSeconds = properties.originalUrlTtl.seconds,
            score = photoViewAssembler.scoreOf(photo),
        )
    }

    /** 임베딩 진행 상황을 확인하는 곳. Lambda는 비동기라 이 집계 말고는 알 방법이 없다. */
    @Transactional(readOnly = true)
    fun summarize(galleryId: Long, userId: Long): PhotoSummaryResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        return PhotoSummaryResponse(
            total = photoRepository.countByGalleryId(galleryId),
            pending = photoRepository.countByGalleryIdAndStatus(galleryId, PhotoStatus.PENDING),
            uploaded = photoRepository.countByGalleryIdAndStatus(galleryId, PhotoStatus.UPLOADED),
            embedded = photoRepository.countByGalleryIdAndStatus(galleryId, PhotoStatus.EMBEDDED),
        )
    }
}
