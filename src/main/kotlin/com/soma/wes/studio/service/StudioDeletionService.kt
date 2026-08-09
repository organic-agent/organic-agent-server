package com.soma.wes.studio.service

import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.studio.config.StudioHardDeletionProperties
import com.soma.wes.studio.dto.request.ExecuteStudioDeletionRequest
import com.soma.wes.studio.dto.response.StudioDeletionResponse
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.support.StudioDeletionPlan
import com.soma.wes.studio.support.StudioDeletionPreparation
import com.soma.wes.studio.support.StudioDeletionProcessor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID


/**
 * 운영자 확인이 끝난 요청만 S3와 DB의 물리 삭제로 연결한다.
 *
 * 일반 [StudioService]는 사용자용 스튜디오 관리만 담당하고, 이 서비스는 관리자 전용 삭제 절차를
 * 담당한다. 삭제는 S3 호출, 멱등성 검사, DB 잠금과 감사 기록까지 필요하므로 일반 CRUD와 분리한다.
 *
 * S3 호출을 DB 트랜잭션에 포함하면 네트워크 응답을 기다리는 동안 DB 커넥션을 점유하고, 트랜잭션을
 * 롤백해도 이미 삭제한 S3 객체는 복구할 수 없다. 따라서 이 서비스에는 `@Transactional`을 선언하지
 * 않고, S3 삭제 전후의 짧은 DB 작업만 [StudioDeletionProcessor]에 위임한다.
 */
@Service
class StudioDeletionService(
    private val processor: StudioDeletionProcessor,
    private val photoStorage: PhotoStorage,
    private val properties: StudioHardDeletionProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun execute(
        studioId: Long,
        operatorUserId: Long,
        requestId: UUID,
        request: ExecuteStudioDeletionRequest,
    ): StudioDeletionResponse {
        val confirmedGalleryUrl = request.confirmedGalleryUrl.trim()
        val reason = request.reason.trim()
        processor.findCompleted(studioId, operatorUserId, requestId, confirmedGalleryUrl, reason)?.let {
            return it
        }

        if (!properties.enabled) {
            throw StudioException(StudioErrorCode.HARD_DELETION_DISABLED)
        }

        validate(confirmedGalleryUrl, reason)

        return when (
            val preparation = processor.prepare(
                studioId = studioId,
                operatorUserId = operatorUserId,
                requestId = requestId,
                confirmedGalleryUrl = confirmedGalleryUrl,
                reason = reason,
            )
        ) {
            is StudioDeletionPreparation.Completed -> preparation.result
            is StudioDeletionPreparation.Pending -> execute(preparation.plan, operatorUserId, reason)
        }
    }

    private fun execute(
        plan: StudioDeletionPlan,
        operatorUserId: Long,
        reason: String,
    ): StudioDeletionResponse {
        try {
            photoStorage.deleteAll(plan.objectKeys)
            val result = processor.delete(plan, operatorUserId, reason)
            log.info(
                "스튜디오 hard delete 완료: studioId={}, operatorUserId={}, requestId={}, galleries={}, photos={}, objects={}",
                result.studioId,
                operatorUserId,
                result.requestId,
                result.galleryCount,
                result.photoCount,
                result.objectCount,
            )
            return result
        } catch (failure: RuntimeException) {
            try {
                processor.markRetryable(plan)
            } catch (markFailure: RuntimeException) {
                log.error(
                    "스튜디오 삭제 claim RUNNING→RETRYABLE 전이 실패; fail-closed claim 수동 확인 필요: " +
                        "studioId={}, requestId={}, claimToken={}, planVersion={}",
                    plan.studioId,
                    plan.requestId,
                    plan.claimToken,
                    plan.planVersion,
                    markFailure,
                )
                failure.addSuppressed(markFailure)
            }
            throw failure
        }
    }

    private fun validate(confirmedGalleryUrl: String, reason: String) {
        if (
            confirmedGalleryUrl.isBlank() || confirmedGalleryUrl.length > MAX_GALLERY_URL_LENGTH ||
            reason.isBlank() || reason.length > MAX_REASON_LENGTH
        ) {
            throw StudioException(StudioErrorCode.INVALID_DELETION_REQUEST)
        }
    }

    companion object {
        private const val MAX_GALLERY_URL_LENGTH = 255
        private const val MAX_REASON_LENGTH = 1000
    }
}
