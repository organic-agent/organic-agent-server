package com.soma.wes.admin.resource.service

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminReprocessScope
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminAnalysisFailuresResponse
import com.soma.wes.admin.resource.dto.AdminReprocessRequest
import com.soma.wes.admin.resource.dto.AdminReprocessResponse
import com.soma.wes.admin.resource.repository.AdminIdempotencyStore
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.port.AiTaskSender
import com.soma.wes.photo.repository.PhotoPipelineRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 관리자 갤러리 재처리 = 분석 데이터 리셋. 갤러리의 `photo_analysis` 행을 지우고 임베더 배정 추적을 초기화하면
 * 스윕이 그 사진을 다시 배정하고(임베딩 → 점수), 잡이 없으면 하나 만들어 categorize·폴더 물질화까지 이어진다.
 * Lambda를 직접 부르지 않는다 — 부르는 것은 스윕의 일이고, 여기서는 "다시 할 대상"만 만든다.
 *
 * 범위는 [AdminReprocessScope]가 정한다 — 전부([AdminReprocessScope.ALL]) 또는 결정적으로 실패한 사진만([AdminReprocessScope.FAILED_ONLY]).
 */
@Service
class AdminReprocessService(
    private val resourceRepository: AdminResourceRepository,
    private val idempotencyStore: AdminIdempotencyStore,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val analysisJobRepository: AnalysisJobRepository,
    private val aiTaskSender: AiTaskSender,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 멱등성 저장소는 자기 트랜잭션(REQUIRES_NEW)이라 이 메서드는 트랜잭션이 없고, 리셋과 잡 생성만 하나로 묶는다. */
    fun reprocess(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: AdminReprocessRequest,
        sourceAddress: String?,
    ): AdminReprocessResponse {
        if (type != AdminResourceType.GALLERY) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        if (!IDEMPOTENCY_KEY.matches(request.idempotencyKey)) {
            throw AdminException(AdminErrorCode.INVALID_IDEMPOTENCY_KEY)
        }
        val gallery = resourceRepository.find(type, id)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        if (gallery.deleted || gallery.version != request.expectedVersion) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        if (!aiTaskSender.isAvailable(AiTaskDto.Embed::class)) {
            throw AnalysisException(AnalysisErrorCode.AI_TASK_NOT_CONFIGURED)
        }

        val targets = countTargets(id, request.scope)
        if (request.scope == AdminReprocessScope.FAILED_ONLY) {
            validateFailedOnly(id, targets)
        }

        val requestHash = sha256("$type:$id:${request.expectedVersion}:${request.reason.trim()}:${request.scope}")
        val reservation = idempotencyStore.reserve(
            action = ACTION,
            idempotencyKey = request.idempotencyKey,
            requestHash = requestHash,
            actorAdminId = actorAdminId,
            targetType = type.auditTargetType,
            targetId = id.toString(),
            targetLabel = gallery.label,
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
            before = mapOf("type" to type.name, "id" to id, "version" to gallery.version, "scope" to request.scope.name),
        )
        reservation.existing?.let { existing ->
            if (existing.status == "COMPLETED") {
                return AdminReprocessResponse(
                    type = type,
                    id = id,
                    idempotencyKey = request.idempotencyKey,
                    scope = request.scope,
                    targets = existing.resultPayload?.toLongOrNull() ?: targets,
                    accepted = false,
                )
            }
            throw AdminException(AdminErrorCode.REPROCESS_ALREADY_REQUESTED)
        }

        try {
            val (reset, jobCreated) = transactionTemplate.execute {
                reset(id, request.scope) to ensureAnalysisJob(id)
            }!!
            idempotencyStore.complete(ACTION, request.idempotencyKey, targets)
            log.info(
                "관리자 분석 재처리(리셋): galleryId={}, 범위={}, 대상={}장, 지운 분석 행={}, 잡 생성={}",
                id, request.scope, targets, reset, jobCreated,
            )
        } catch (e: RuntimeException) {
            idempotencyStore.fail(ACTION, request.idempotencyKey, e.javaClass.simpleName)
            throw e
        }

        return AdminReprocessResponse(
            type = type,
            id = id,
            idempotencyKey = request.idempotencyKey,
            scope = request.scope,
            targets = targets,
            accepted = true,
        )
    }

    /** 리셋 뒤 다시 임베딩될 사진 수 — S3 객체가 없을 수 있는 PENDING 은 제외한다. */
    private fun countTargets(galleryId: Long, scope: AdminReprocessScope): Long {
        val progress = photoPipelineRepository.progressOf(galleryId, liveSince = ZonedDateTime.now(clock))
        return when (scope) {
            AdminReprocessScope.ALL -> progress.uploaded
            AdminReprocessScope.FAILED_ONLY -> progress.failed
        }
    }

    /**
     * 실패한 사진만 되살리는 요청을 거른다. 되살릴 사진이 없으면 잡을 만들지 않는다 — 다 끝난 갤러리에 빈 잡이 생기면 categorize·물질화를
     * 한 번 더 돌린다. 잡이 CATEGORIZING 이면 받지 않는다 — 이미 보낸 categorize 는 되살린 사진을 보지 않는데 잡은 그 사진을 기다린다.
     */
    private fun validateFailedOnly(galleryId: Long, targets: Long) {
        if (targets == 0L) {
            throw AdminException(AdminErrorCode.NO_FAILED_ANALYSIS)
        }
        if (analysisJobRepository.existsByGalleryIdAndStatusIn(galleryId, setOf(AnalysisStatus.CATEGORIZING))) {
            throw AdminException(AdminErrorCode.ANALYSIS_CATEGORIZING)
        }
    }

    private fun reset(galleryId: Long, scope: AdminReprocessScope): Int = when (scope) {
        AdminReprocessScope.ALL -> photoPipelineRepository.resetAnalysis(galleryId)
        AdminReprocessScope.FAILED_ONLY -> photoPipelineRepository.resetFailedAnalysis(galleryId).size
    }

    /** 살아 있는 잡이 있으면 그 잡이 리셋된 사진을 다시 관측한다. 없으면 하나 만든다 — 폴더 물질화까지 자동으로 잇기 위해서다. */
    private fun ensureAnalysisJob(galleryId: Long): Boolean {
        if (analysisJobRepository.existsByGalleryIdAndStatusIn(galleryId, AnalysisStatus.ACTIVE)) return false
        analysisJobRepository.save(AnalysisJob(galleryId = galleryId))
        return true
    }

    /**
     * 갤러리에서 분석이 결정적으로 실패한 사진과 그 사유 — [AdminReprocessScope.FAILED_ONLY] 재처리가 되돌릴 대상이다.
     * 사유는 `photo_analysis.error` 값 그대로라 이 서버가 쓴 것(`EMBED_ATTEMPTS_EXCEEDED`·`ANALYSIS_STALLED`)과 AI 실행기가 쓴 것이 섞인다.
     */
    fun getAnalysisFailures(galleryId: Long): AdminAnalysisFailuresResponse {
        val gallery = resourceRepository.find(AdminResourceType.GALLERY, galleryId)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        if (gallery.deleted) {
            throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        }

        val failures = photoPipelineRepository.findAnalysisFailures(galleryId)

        return AdminAnalysisFailuresResponse(
            galleryId = galleryId,
            failed = failures.size,
            photos = failures.map { failure ->
                AdminAnalysisFailuresResponse.Photo(
                    photoId = failure.photoId,
                    originalFileName = failure.originalFileName,
                    error = failure.error,
                    failedAt = failure.failedAt,
                )
            },
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val ACTION = "GALLERY_EMBEDDING"
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{8,128}$")
    }
}
