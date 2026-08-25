package com.soma.wes.admin.resource.service

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminReprocessRequest
import com.soma.wes.admin.resource.dto.AdminReprocessResponse
import com.soma.wes.admin.resource.repository.AdminIdempotencyStore
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import com.soma.wes.embedding.service.EmbeddingInvoker
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
class AdminReprocessService(
    private val resourceRepository: AdminResourceRepository,
    private val idempotencyStore: AdminIdempotencyStore,
    private val jdbcClient: JdbcClient,
    private val embeddingInvoker: EmbeddingInvoker,
) {

    private val log = LoggerFactory.getLogger(javaClass)

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
        if (!embeddingInvoker.isAvailable) {
            throw PhotoException(PhotoErrorCode.EMBEDDING_NOT_CONFIGURED)
        }

        val targets = countTargets(id, request.force)
        val requestHash = sha256("$type:$id:${request.expectedVersion}:${request.force}:${request.reason.trim()}")
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
            embeddingInvoker.invoke(id, request.force)
            idempotencyStore.complete(ACTION, request.idempotencyKey, targets)
            log.info("관리자 임베딩 재처리 요청: galleryId={}, force={}, 대상={}장", id, request.force, targets)
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

    private fun countTargets(galleryId: Long, force: Boolean): Long {
        val embeddingCondition = if (force) "" else " AND embedding IS NULL"
        return jdbcClient.sql(
            """
                SELECT COUNT(*)
                FROM photos
                WHERE gallery_id = :galleryId
                  AND deleted_at IS NULL
                  AND status <> :pendingStatus
                  $embeddingCondition
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("pendingStatus", PhotoStatus.PENDING.name)
            .query { rs, _ -> rs.getLong(1) }
            .single()
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
