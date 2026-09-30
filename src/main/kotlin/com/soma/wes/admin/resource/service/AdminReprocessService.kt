package com.soma.wes.admin.resource.service

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
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
 * 스윕이 사진 전부를 다시 배정하고(임베딩 → 점수), 잡이 없으면 하나 만들어 categorize·폴더 물질화까지 이어진다.
 * Lambda를 직접 부르지 않는다 — 부르는 것은 스윕의 일이고, 여기서는 "다시 할 대상"만 만든다.
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

        val targets = countTargets(id)
        val requestHash = sha256("$type:$id:${request.expectedVersion}:${request.reason.trim()}")
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
            before = mapOf("type" to type.name, "id" to id, "version" to gallery.version),
        )
        reservation.existing?.let { existing ->
            if (existing.status == "COMPLETED") {
                return AdminReprocessResponse(
                    type = type,
                    id = id,
                    idempotencyKey = request.idempotencyKey,
                    targets = existing.resultPayload?.toLongOrNull() ?: targets,
                    accepted = false,
                )
            }
            throw AdminException(AdminErrorCode.REPROCESS_ALREADY_REQUESTED)
        }

        try {
            val (reset, jobCreated) = transactionTemplate.execute {
                photoPipelineRepository.resetAnalysis(id) to ensureAnalysisJob(id)
            }!!
            idempotencyStore.complete(ACTION, request.idempotencyKey, targets)
            log.info("관리자 분석 재처리(리셋): galleryId={}, 대상={}장, 지운 분석 행={}, 잡 생성={}", id, targets, reset, jobCreated)
        } catch (e: RuntimeException) {
            idempotencyStore.fail(ACTION, request.idempotencyKey, e.javaClass.simpleName)
            throw e
        }

        return AdminReprocessResponse(
            type = type,
            id = id,
            idempotencyKey = request.idempotencyKey,
            targets = targets,
            accepted = true,
        )
    }

    /** 살아 있는 잡이 있으면 그 잡이 리셋된 사진을 다시 관측한다. 없으면 하나 만든다 — 폴더 물질화까지 자동으로 잇기 위해서다. */
    private fun ensureAnalysisJob(galleryId: Long): Boolean {
        if (analysisJobRepository.existsByGalleryIdAndStatusIn(galleryId, AnalysisStatus.ACTIVE)) return false
        analysisJobRepository.save(AnalysisJob(galleryId = galleryId))
        return true
    }

    /** 리셋 뒤 다시 임베딩될 사진 수 — S3 객체가 없을 수 있는 PENDING 은 제외한다. */
    private fun countTargets(galleryId: Long): Long =
        photoPipelineRepository.progressOf(galleryId, liveSince = ZonedDateTime.now(clock)).uploaded

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val ACTION = "GALLERY_EMBEDDING"
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{8,128}$")
    }
}
