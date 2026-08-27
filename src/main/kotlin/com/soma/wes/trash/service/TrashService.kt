package com.soma.wes.trash.service

import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.trash.config.TrashProperties
import com.soma.wes.trash.dto.request.EraseTrashedPhotosRequest
import com.soma.wes.trash.dto.request.RestorePhotosRequest
import com.soma.wes.trash.dto.response.TrashedGalleryResponse
import com.soma.wes.trash.dto.response.TrashedPhotoListResponse
import com.soma.wes.trash.dto.response.TrashedPhotoResponse
import com.soma.wes.trash.exception.TrashErrorCode
import com.soma.wes.trash.exception.TrashException
import com.soma.wes.trash.repository.TrashRepository
import com.soma.wes.trash.repository.projection.TrashedPhotoTarget
import com.soma.wes.trash.support.TrashEraser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 휴지통 화면과 그 세 동작 — 목록, 복원, 즉시 물리 삭제.
 *
 * 휴지통으로 *보내는* 것은 소유 도메인([com.soma.wes.gallery.service.GalleryService],
 * [com.soma.wes.photo.service.PhotoService])의 일이다. 살아 있는 갤러리로 인가하고 엔티티를
 * 고치면 되기 때문이다. 여기부터는 숨은 행의 세계라 [TrashRepository]의 네이티브 SQL을 쓴다.
 *
 * 인가가 둘로 갈리는 이유: 사진 휴지통은 갤러리가 살아 있으므로 [GalleryAccessPolicy]를
 * 그대로 지나지만, 갤러리 휴지통은 대상이 숨어 있어 그 관문을 지날 수 없다. 대신 쿼리의
 * `studio_id` 조건이 인가를 겸한다 — 내 스튜디오의 휴지통 행이 아니면 없는 것과 같다.
 *
 * 물리 삭제의 S3·DB 경계와 관리자 배치 경쟁 제어는 [TrashEraser]가 담당한다.
 */
@Service
class TrashService(
    private val trashRepository: TrashRepository,
    private val trashEraser: TrashEraser,
    private val studioRepository: StudioRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoStorage: PhotoStorage,
    private val trashProperties: TrashProperties,
    private val storageProperties: StorageProperties,
    private val clock: Clock,
) {

    // --- 갤러리 휴지통 ---

    /** 내 스튜디오의 휴지통 갤러리. 스튜디오가 없으면(부부 계정) 빈 목록이다. */
    @Transactional(readOnly = true)
    fun listGalleries(userId: Long): List<TrashedGalleryResponse> {
        val studio = studioRepository.findByUserId(userId) ?: return emptyList()

        return trashRepository.findTrashedGalleries(checkNotNull(studio.id) { "저장되지 않은 스튜디오입니다." })
            .map { TrashedGalleryResponse.of(it, trashProperties.retention) }
    }

    /**
     * 갤러리를 휴지통에서 되살린다. 사진 행은 건드린 적이 없으므로 지우기 전 모습 그대로다 —
     * 갤러리보다 먼저 개별 삭제된 사진은 복원 뒤에도 휴지통에 남는다.
     */
    @Transactional
    fun restoreGallery(galleryId: Long, userId: Long) {
        val studioId = requireStudioId(userId)

        if (trashRepository.restoreGallery(galleryId, studioId) == 0) {
            throw TrashException(TrashErrorCode.GALLERY_NOT_IN_TRASH)
        }
    }

    /**
     * 갤러리를 사진 원본·미리보기와 함께 즉시 물리 삭제한다. 복구할 수 없다.
     *
     * 아직 유효한 업로드 URL이 있으면 거절한다 — 삭제 뒤 그 URL로 PUT이 오면 아무 행도
     * 가리키지 않는 객체가 남는다. URL 수명(30분)이 지나면 다시 시도할 수 있다.
     */
    fun eraseGallery(galleryId: Long, userId: Long) {
        val studioId = requireStudioId(userId)
        if (!trashRepository.isTrashedGalleryOfForPurge(galleryId, studioId)) {
            throw TrashException(TrashErrorCode.GALLERY_NOT_IN_TRASH)
        }
        requireNoActiveUploadUrls(trashRepository.findAllPhotoTargets(galleryId))

        if (!trashEraser.eraseGallery(galleryId)) {
            throw TrashException(TrashErrorCode.GALLERY_NOT_IN_TRASH)
        }
    }

    /** 갤러리 휴지통 연산의 인가 재료. 스튜디오가 없다는 것도 "내 휴지통에 없다"로 답한다. */
    private fun requireStudioId(userId: Long): Long {
        val studio = studioRepository.findByUserId(userId)
            ?: throw TrashException(TrashErrorCode.GALLERY_NOT_IN_TRASH)
        return checkNotNull(studio.id) { "저장되지 않은 스튜디오입니다." }
    }

    // --- 사진 휴지통 ---

    @Transactional(readOnly = true)
    fun listPhotos(galleryId: Long, userId: Long): TrashedPhotoListResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val photos = trashRepository.findTrashedPhotos(galleryId).map { row ->
            TrashedPhotoResponse.of(
                row = row,
                retention = trashProperties.retention,
                // PENDING은 S3에 객체가 없을 수 있다. 목록·상세와 같은 규칙이다.
                viewUrl = if (row.status == PhotoStatus.PENDING) {
                    null
                } else {
                    photoStorage.presignView(row.previewKey ?: row.storageKey)
                },
            )
        }
        return TrashedPhotoListResponse(
            photos = photos,
            viewUrlTtlSeconds = storageProperties.viewUrlTtl.seconds,
        )
    }

    /**
     * 사진들을 휴지통에서 되살린다. 전부-아니면-거부다 — UPDATE의 조건이 검증을 겸하므로,
     * 갱신된 수가 요청과 다르면 예외로 트랜잭션째 되돌린다.
     */
    @Transactional
    fun restorePhotos(galleryId: Long, userId: Long, request: RestorePhotosRequest) {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val photoIds = requireBatchSize(request.photoIds)
        if (trashRepository.restorePhotos(galleryId, photoIds) != photoIds.size) {
            throw TrashException(TrashErrorCode.PHOTO_NOT_IN_TRASH)
        }
    }

    /** 사진들을 원본·미리보기와 함께 즉시 물리 삭제한다. 거절 규칙은 [eraseGallery]와 같다. */
    fun erasePhotos(galleryId: Long, userId: Long, request: EraseTrashedPhotosRequest) {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val photoIds = requireBatchSize(request.photoIds)
        val targets = trashRepository.findTrashedPhotoTargets(galleryId, photoIds)
        if (targets.size != photoIds.size) {
            throw TrashException(TrashErrorCode.PHOTO_NOT_IN_TRASH)
        }
        requireNoActiveUploadUrls(targets)

        if (!trashEraser.erasePhotos(targets)) {
            throw TrashException(TrashErrorCode.PHOTO_NOT_IN_TRASH)
        }
    }

    /** [restorePhotos]와 [erasePhotos]가 쓴다. 업로드·조회와 같은 배치 상한을 지킨다. */
    private fun requireBatchSize(photoIds: List<Long>): Set<Long> {
        if (photoIds.size > storageProperties.maxBatchSize) {
            throw PhotoException(PhotoErrorCode.TOO_MANY_PHOTOS)
        }
        return photoIds.toSet()
    }

    private fun requireNoActiveUploadUrls(targets: List<TrashedPhotoTarget>) {
        val now = clock.instant()
        if (targets.any { target -> target.uploadUrlExpiresAt?.let { !it.isBefore(now) } == true }) {
            throw TrashException(TrashErrorCode.UPLOAD_URL_ACTIVE)
        }
    }
}
