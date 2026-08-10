package com.soma.wes.studio.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.domain.StudioDeletionAudit
import com.soma.wes.studio.domain.StudioDeletionClaimState
import com.soma.wes.studio.dto.response.StudioDeletionResponse
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioDeletionAuditRepository
import com.soma.wes.studio.repository.StudioDeletionClaimRepository
import com.soma.wes.studio.repository.StudioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
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
    private val claimRepository: StudioDeletionClaimRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun findCompleted(
        studioId: Long,
        operatorUserId: Long,
        requestId: UUID,
        confirmedGalleryUrl: String,
        reason: String,
    ): StudioDeletionResponse? =
        completedResult(studioId, operatorUserId, requestId, confirmedGalleryUrl, reason)

    @Transactional
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

        val studio = studioRepository.findByIdForUpdate(studioId)
            ?: return completedResult(studioId, operatorUserId, requestId, confirmedGalleryUrl, reason)
                ?.let(StudioDeletionPreparation::Completed)
                ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        requireConfirmedTarget(studio, confirmedGalleryUrl)

        val retryableClaim = claimRepository.findByStudioId(studioId)?.let { existing ->
            if (existing.requestId != requestId) {
                throw StudioException(StudioErrorCode.DELETION_REQUEST_IN_PROGRESS)
            }
            requireSameRequest(
                existing.studioId,
                existing.operatorUserId,
                existing.studioGalleryUrl,
                existing.reason,
                studioId,
                operatorUserId,
                confirmedGalleryUrl,
                reason,
            )
            if (existing.state != StudioDeletionClaimState.RETRYABLE) {
                throw StudioException(StudioErrorCode.DELETION_REQUEST_IN_PROGRESS)
            }
            requireSupportedPlanVersion(existing.planVersion)
            existing
        }

        if (!studioRepository.tryAcquireDeletionWriteFence(StudioWriteFence.key(studioId))) {
            throw StudioException(StudioErrorCode.DELETION_WRITER_IN_PROGRESS)
        }

        val claimToken = UUID.randomUUID()
        val claimed = if (retryableClaim == null) {
            claimRepository.tryClaim(
                requestId = requestId,
                claimToken = claimToken,
                studioId = studioId,
                operatorUserId = operatorUserId,
                studioGalleryUrl = confirmedGalleryUrl,
                reason = reason,
                planVersion = CURRENT_SUPPORTED_PLAN_VERSION,
            )
        } else {
            claimRepository.tryResume(
                requestId,
                retryableClaim.claimToken,
                claimToken,
                CURRENT_SUPPORTED_PLAN_VERSION,
            )
        }
        if (claimed == 0) {
            completedResult(studioId, operatorUserId, requestId, confirmedGalleryUrl, reason)?.let {
                return StudioDeletionPreparation.Completed(it)
            }
            val existing = claimRepository.findById(requestId)
                .orElseThrow { StudioException(StudioErrorCode.DELETION_REQUEST_IN_PROGRESS) }
            requireSameRequest(
                existing.studioId,
                existing.operatorUserId,
                existing.studioGalleryUrl,
                existing.reason,
                studioId,
                operatorUserId,
                confirmedGalleryUrl,
                reason,
            )
            throw StudioException(StudioErrorCode.DELETION_REQUEST_IN_PROGRESS)
        }

        completedResult(studioId, operatorUserId, requestId, confirmedGalleryUrl, reason)?.let {
            claimRepository.release(requestId, claimToken)
            return StudioDeletionPreparation.Completed(it)
        }

        val galleries = galleryRepository.findAllByStudioId(studioId)
        val photos = findPhotos(galleries)
        requireNoActiveUploadUrls(photos)

        return StudioDeletionPreparation.Pending(snapshot(requestId, claimToken, studio, galleries, photos))
    }

    private fun requireNoActiveUploadUrls(photos: List<Photo>) {
        val now = clock.instant()
        if (photos.any { photo -> photo.uploadUrlExpiresAt?.let { !it.isBefore(now) } == true }) {
            throw StudioException(StudioErrorCode.DELETION_UPLOAD_URL_ACTIVE)
        }
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
        )?.let {
            claimRepository.release(plan.requestId, plan.claimToken)
            return it
        }

        requireSupportedPlanVersion(plan.planVersion)

        val claim = claimRepository.findOwnedRunningForUpdate(plan.requestId, plan.claimToken)
            ?: throw StudioException(StudioErrorCode.DELETION_REQUEST_IN_PROGRESS)
        if (claim.planVersion != plan.planVersion) {
            throw StudioException(StudioErrorCode.DELETION_PLAN_VERSION_UNSUPPORTED)
        }
        requireSameRequest(
            claim.studioId,
            claim.operatorUserId,
            claim.studioGalleryUrl,
            claim.reason,
            plan.studioId,
            operatorUserId,
            plan.studioGalleryUrl,
            reason,
        )

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
        val current = snapshot(plan.requestId, plan.claimToken, studio, galleries, photos)
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
        claimRepository.delete(claim)
        return StudioDeletionResponse.from(audit)
    }

    /** S3 호출 전에만 사용하는 abort. post-S3 실패는 [markRetryable]로 fail-closed 처리한다. */
    @Transactional
    fun release(plan: StudioDeletionPlan) {
        claimRepository.release(plan.requestId, plan.claimToken)
    }

    /** S3 삭제가 일부라도 시작된 실패는 claim을 지우지 않고 같은 요청의 재개만 허용한다. */
    @Transactional
    fun markRetryable(plan: StudioDeletionPlan) {
        if (claimRepository.markRetryable(plan.requestId, plan.claimToken, plan.planVersion) == 0) {
            throw StudioException(StudioErrorCode.DELETION_REQUEST_IN_PROGRESS)
        }
    }

    private fun requireSupportedPlanVersion(planVersion: Int) {
        if (planVersion != CURRENT_SUPPORTED_PLAN_VERSION) {
            throw StudioException(StudioErrorCode.DELETION_PLAN_VERSION_UNSUPPORTED)
        }
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

    private fun requireSameRequest(
        claimedStudioId: Long,
        claimedOperatorUserId: Long,
        claimedGalleryUrl: String,
        claimedReason: String,
        studioId: Long,
        operatorUserId: Long,
        confirmedGalleryUrl: String,
        reason: String,
    ) {
        if (
            claimedStudioId != studioId ||
            claimedOperatorUserId != operatorUserId ||
            claimedGalleryUrl != confirmedGalleryUrl ||
            claimedReason != reason
        ) {
            throw StudioException(StudioErrorCode.DELETION_REQUEST_CONFLICT)
        }
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
        claimToken: UUID,
        studio: Studio,
        galleries: List<Gallery>,
        photos: List<Photo>,
    ): StudioDeletionPlan =
        StudioDeletionPlan(
            requestId = requestId,
            claimToken = claimToken,
            studioId = checkNotNull(studio.id) { "저장되지 않은 스튜디오입니다." },
            studioUserId = studio.userId,
            studioGalleryUrl = studio.galleryUrl,
            planVersion = CURRENT_SUPPORTED_PLAN_VERSION,
            galleryIds = galleries.mapTo(mutableSetOf()) {
                checkNotNull(it.id) { "저장되지 않은 갤러리입니다." }
            },
            photos = photos.mapTo(mutableSetOf()) {
                PhotoDeletionTarget(
                    photoId = it.requiredId,
                    storageKey = it.storageKey,
                    previewKey = it.previewKey,
                    storageOwnership = it.storageOwnership,
                )
            },
        )

    companion object {
        /**
         * V11 이전 사진은 모두 GALLERY로 backfill되고 기존 key 집합은 그대로다.
         * 새 SHARED_TEMPLATE 생성은 이 plan의 claim·fence에 막히므로 v1 재개와 호환된다.
         * 기존 GALLERY의 snapshot·key 의미를 바꾸는 경우에만 재개 지원과 함께 버전을 올린다.
         */
        const val CURRENT_SUPPORTED_PLAN_VERSION = 1
    }
}
