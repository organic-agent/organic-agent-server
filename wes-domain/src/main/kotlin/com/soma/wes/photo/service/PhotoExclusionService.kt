package com.soma.wes.photo.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.ChangePhotoExclusionsRequest
import com.soma.wes.photo.dto.response.PhotoCountResponse
import com.soma.wes.photo.dto.response.PhotoPageResponse
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PhotoExclusionService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val selectionRepository: PhotoSelectionRepository,
    private val selectionItems: PhotoSelectionItemRepository,
    private val assembler: PhotoViewAssembler,
    private val properties: StorageProperties,
    private val activityRecorder: ActivityRecorder,
    private val clock: Clock,
) {
    /** 작가와 부부는 확정 이후에도 제외 목록을 읽는다. 원본 삭제용 휴지통과는 별개다. */
    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long, page: Int, size: Int): PhotoPageResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        val pageable = PageRequests.of(page, size, Sort.by(Sort.Direction.ASC, "displayOrder", "id"))
        val found = photoRepository.findAllByGalleryIdAndExcludedAtIsNotNull(galleryId, pageable)
        val studioViewer = galleryAccessPolicy.isStudioManager(galleryId, userId)
        val photos = assembler.toResponses(found.content).map { photo ->
            if (studioViewer) photo.copy(score = null, ratedBy = null, ratedAt = null, memo = null) else photo
        }
        return PhotoPageResponse.of(
            page = PageResponse.of(found, photos),
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
            viewUrlsExpire = assembler.viewUrlsExpire(photos.map { it.viewUrl }),
        )
    }

    /** 선택 해제와 제외 상태는 함께 커밋한다. 복원에 필요한 폴더·공유 연결은 지우지 않는다. */
    @Transactional
    fun exclude(galleryId: Long, userId: Long, request: ChangePhotoExclusionsRequest): PhotoCountResponse {
        val gallery = galleryAccessPolicy.requireParticipantWriter(galleryId, userId)
        val ids = validateAndLock(gallery, request)

        val selection = selectionRepository.findByGalleryId(galleryId)
        if (selection != null) selectionItems.deleteAllBySelectionIdAndPhotoIdIn(selection.requiredId, ids)
        val count = photoRepository.exclude(galleryId, ids, userId, ZonedDateTime.now(clock))
        if (count > 0) activityRecorder.recordGallery(galleryId)
        return PhotoCountResponse(count)
    }

    /** 표시만 복원하고 이전 선택은 복구하지 않는다. */
    @Transactional
    fun restore(galleryId: Long, userId: Long, request: ChangePhotoExclusionsRequest): PhotoCountResponse {
        val gallery = galleryAccessPolicy.requireParticipantWriter(galleryId, userId)
        val ids = validateAndLock(gallery, request)

        val count = photoRepository.restoreExclusions(galleryId, ids, ZonedDateTime.now(clock))
        if (count > 0) activityRecorder.recordGallery(galleryId)
        return PhotoCountResponse(count)
    }

    private fun validateAndLock(gallery: Gallery, request: ChangePhotoExclusionsRequest): Set<Long> {
        if (gallery.stage !in setOf(GalleryStage.UPLOAD, GalleryStage.SELECTION_IN_PROGRESS)) {
            throw PhotoException(PhotoErrorCode.EXCLUSIONS_LOCKED)
        }
        selectionRepository.findByGalleryId(gallery.requiredId)?.requireEditable()
        if (request.photoIds.isEmpty()) throw PhotoException(PhotoErrorCode.EMPTY_EXCLUSION_IDS)
        if (request.photoIds.size > properties.maxBatchSize) throw PhotoException(PhotoErrorCode.TOO_MANY_PHOTOS)

        val ids = request.photoIds.toSet()
        val photos = photoRepository.findAllWithLockByGalleryIdAndIdIn(gallery.requiredId, ids)
        if (photos.size != ids.size) throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)
        if (photos.any { it.status != PhotoStatus.UPLOADED }) throw PhotoException(PhotoErrorCode.EXCLUSION_NOT_UPLOADED)
        return ids
    }
}
