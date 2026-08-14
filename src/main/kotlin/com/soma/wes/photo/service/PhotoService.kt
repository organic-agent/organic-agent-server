package com.soma.wes.photo.service

import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoRating
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.CompleteUploadRequest
import com.soma.wes.photo.dto.request.DeletePhotosRequest
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
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 원본 사진의 업로드와 조회.
 */
@Service
class PhotoService(
    private val photoRepository: PhotoRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoStorage: PhotoStorage,
    private val photoViewAssembler: PhotoViewAssembler,
    private val properties: StorageProperties,
    private val clock: Clock,
) {

    companion object {
        /** 받아줄 이미지 형식. */
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
     */
    @Transactional
    fun issueUploadUrls(
        galleryId: Long,
        userId: Long,
        request: IssueUploadUrlsRequest,
    ): IssueUploadUrlsResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

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
        val orderBase = photoRepository.nextDisplayOrder(galleryId)

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

        val photos = checkAndLoadPhotos(galleryId, request.photoIds)
        photos.forEach { it.markUploaded() }
        return PhotoCountResponse(photos.size)
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
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val photos = checkAndLoadPhotos(galleryId, request.photoIds)
        val now = ZonedDateTime.now(clock)
        photos.forEach { it.moveToTrash(now) }
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

        val found = findPage(galleryId, status, minScore, pageableOf(page, size))

        return PhotoPageResponse.of(
            page = PageResponse.of(found, photoViewAssembler.toResponses(found.content)),
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
