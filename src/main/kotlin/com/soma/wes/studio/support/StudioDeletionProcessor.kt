package com.soma.wes.studio.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.domain.StudioDeletionAudit
import com.soma.wes.studio.dto.response.StudioDeletionResponse
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioDeletionAuditRepository
import com.soma.wes.studio.repository.StudioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID


/**
 * DB 스냅샷과 최종 cascade delete를 각각 짧은 트랜잭션으로 수행한다.
 *
 * Spring의 `@Transactional`은 외부에서 프록시를 거쳐 호출할 때 적용되므로, 삭제 서비스 안에서
 * 자기 자신의 트랜잭션 메서드를 호출하면 선언이 무시된다. S3 호출과 DB 트랜잭션을 분리하면서
 * 트랜잭션 경계도 보장하기 위해 별도 Spring Bean으로 둔다.
 */
@Service
class StudioDeletionProcessor(
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val auditRepository: StudioDeletionAuditRepository,
) {

    @Transactional(readOnly = true)
    fun prepare(
        studioId: Long,
        operatorUserId: Long,
        requestId: UUID,
        confirmedGalleryUrl: String,
        reason: String,
    ): StudioDeletionPreparation {
        completedResult(studioId, operatorUserId, requestId, confirmedGalleryUrl, reason)?.let {
            return StudioDeletionPreparation.Completed(it)
        }

        val studio = studioRepository.findById(studioId)
            .orElseThrow { StudioException(StudioErrorCode.STUDIO_NOT_FOUND) }
        requireConfirmedTarget(studio, confirmedGalleryUrl)

        val galleries = galleryRepository.findAllByStudioId(studioId)
        val photos = findPhotos(galleries)
        return StudioDeletionPreparation.Pending(snapshot(requestId, studio, galleries, photos))
    }

    @Transactional
    fun delete(
        plan: StudioDeletionPlan,
        operatorUserId: Long,
        reason: String,
    ): StudioDeletionResponse {
        completedResult(
            plan.studioId,
            operatorUserId,
            plan.requestId,
            plan.studioGalleryUrl,
            reason,
        )?.let { return it }

        val studio = studioRepository.findByIdForUpdate(plan.studioId)
            ?: return completedResult(
                plan.studioId,
                operatorUserId,
                plan.requestId,
                plan.studioGalleryUrl,
                reason,
            )
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        requireConfirmedTarget(studio, plan.studioGalleryUrl)

        // 부모 행을 잠근 뒤 자식도 잠근다. V4의 FK와 함께 새 갤러리·사진이 cascade 경계에
        // 뒤늦게 끼어들지 못하게 한다.
        val galleries = galleryRepository.findAllByStudioIdForUpdate(plan.studioId)
        val photos = findPhotosForUpdate(galleries)
        val current = snapshot(plan.requestId, studio, galleries, photos)
        if (current.galleryIds != plan.galleryIds || current.photos != plan.photos) {
            throw StudioException(StudioErrorCode.DELETION_TARGET_CHANGED)
        }

        // galleries → invites/members/photos는 DB FK의 ON DELETE CASCADE가 한 트랜잭션에서 지운다.
        studioRepository.delete(studio)
        studioRepository.flush()

        val audit = auditRepository.saveAndFlush(
            StudioDeletionAudit(
                requestId = plan.requestId,
                studioId = plan.studioId,
                studioUserId = plan.studioUserId,
                operatorUserId = operatorUserId,
                studioGalleryUrl = plan.studioGalleryUrl,
                reason = reason,
                galleryCount = plan.galleryIds.size,
                photoCount = plan.photos.size,
                objectCount = plan.objectKeys.size,
            ),
        )
        return StudioDeletionResponse.from(audit)
    }

    private fun completedResult(
        studioId: Long,
        operatorUserId: Long,
        requestId: UUID,
        confirmedGalleryUrl: String,
        reason: String,
    ): StudioDeletionResponse? {
        val audit = auditRepository.findByRequestId(requestId) ?: return null
        if (
            audit.studioId != studioId ||
            audit.operatorUserId != operatorUserId ||
            audit.studioGalleryUrl != confirmedGalleryUrl ||
            audit.reason != reason
        ) {
            throw StudioException(StudioErrorCode.DELETION_REQUEST_CONFLICT)
        }
        return StudioDeletionResponse.from(audit)
    }

    private fun requireConfirmedTarget(studio: Studio, confirmedGalleryUrl: String) {
        if (studio.galleryUrl != confirmedGalleryUrl) {
            throw StudioException(StudioErrorCode.DELETION_TARGET_MISMATCH)
        }
    }

    private fun findPhotos(galleries: List<Gallery>): List<Photo> {
        val galleryIds = galleries.mapNotNull { it.id }
        return if (galleryIds.isEmpty()) emptyList() else photoRepository.findAllByGalleryIdIn(galleryIds)
    }

    private fun findPhotosForUpdate(galleries: List<Gallery>): List<Photo> {
        val galleryIds = galleries.mapNotNull { it.id }
        return if (galleryIds.isEmpty()) emptyList() else photoRepository.findAllByGalleryIdInForUpdate(galleryIds)
    }

    private fun snapshot(
        requestId: UUID,
        studio: Studio,
        galleries: List<Gallery>,
        photos: List<Photo>,
    ): StudioDeletionPlan =
        StudioDeletionPlan(
            requestId = requestId,
            studioId = checkNotNull(studio.id) { "저장되지 않은 스튜디오입니다." },
            studioUserId = studio.userId,
            studioGalleryUrl = studio.galleryUrl,
            galleryIds = galleries.mapTo(mutableSetOf()) {
                checkNotNull(it.id) { "저장되지 않은 갤러리입니다." }
            },
            photos = photos.mapTo(mutableSetOf()) {
                PhotoDeletionTarget(
                    photoId = it.requiredId,
                    storageKey = it.storageKey,
                    previewKey = it.previewKey,
                )
            },
        )
}
