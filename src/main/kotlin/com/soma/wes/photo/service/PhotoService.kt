package com.soma.wes.photo.service

import com.soma.wes.gallery.service.GalleryAccessPolicy
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.CompleteUploadRequest
import com.soma.wes.photo.dto.request.IssueUploadUrlsRequest
import com.soma.wes.photo.dto.response.IssueUploadUrlsResponse
import com.soma.wes.photo.dto.response.IssuedUploadResponse
import com.soma.wes.photo.dto.response.PhotoCountResponse
import com.soma.wes.photo.dto.response.PhotoPageResponse
import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.photo.dto.response.PhotoSummaryResponse
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoRepository
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
 * 모든 경로가 [GalleryAccessPolicy.requireManager]를 지난다. 사진을 올리고 지우고 다시
 * 정렬하는 것은 담당 작가의 일이고, 예비 부부는 [갤러리 조회][com.soma.wes.gallery.service.GalleryService]와
 * 선택 API로만 사진을 만난다.
 */
@Service
@Transactional(readOnly = true)
class PhotoService(
    private val photoRepository: PhotoRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoStorage: PhotoStorage,
    private val properties: StorageProperties,
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
        galleryAccessPolicy.requireManager(galleryId, userId)

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
            IssuedUploadResponse(
                photoId = photo.requiredId,
                storageKey = photo.storageKey,
                uploadUrl = photoStorage.presignUpload(photo.storageKey, photo.contentType),
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
        galleryAccessPolicy.requireManager(galleryId, userId)

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
     * 작가의 사진 목록.
     *
     * 사진마다 서명된 조회 URL이 붙어 온다. 버킷이 비공개라 `storageKey`만으로는 아무것도
     * 띄울 수 없어서, 그 URL이 이미지를 화면에 그리는 유일한 통로다.
     */
    fun list(
        galleryId: Long,
        userId: Long,
        status: PhotoStatus?,
        page: Int,
        size: Int,
    ): PhotoPageResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)

        if (size !in 1..properties.maxBatchSize) {
            throw PhotoException(PhotoErrorCode.TOO_MANY_PHOTOS)
        }

        // displayOrder가 같은 사진(같은 배치에 동시 발급된 것들)이 페이지를 넘길 때마다
        // 자리를 바꾸지 않도록 id로 한 번 더 정렬한다.
        val pageable = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.ASC, "displayOrder", "id"),
        )
        val found = if (status == null) {
            photoRepository.findAllByGalleryId(galleryId, pageable)
        } else {
            photoRepository.findAllByGalleryIdAndStatus(galleryId, status, pageable)
        }

        return PhotoPageResponse(
            photos = found.content.map(::toResponse),
            page = found.number,
            size = found.size,
            totalCount = found.totalElements,
            hasNext = found.hasNext(),
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    private fun toResponse(photo: Photo) = PhotoResponse.of(
        photo = photo,
        // PENDING은 URL만 발급되고 실제 객체는 아직 없을 수 있다. URL을 주면 프론트의
        // <img>가 깨진 이미지를 그리므로, 올라온 것이 확실한 사진에만 채운다.
        viewUrl = if (photo.status == PhotoStatus.PENDING) null else photoStorage.presignView(photo.storageKey),
    )

    /** 임베딩 진행 상황을 확인하는 곳. Lambda는 비동기라 이 집계 말고는 알 방법이 없다. */
    fun summarize(galleryId: Long, userId: Long): PhotoSummaryResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)

        return PhotoSummaryResponse(
            total = photoRepository.countByGalleryId(galleryId),
            pending = photoRepository.countByGalleryIdAndStatus(galleryId, PhotoStatus.PENDING),
            uploaded = photoRepository.countByGalleryIdAndStatus(galleryId, PhotoStatus.UPLOADED),
            embedded = photoRepository.countByGalleryIdAndStatus(galleryId, PhotoStatus.EMBEDDED),
        )
    }
}
