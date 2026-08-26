package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminReasonRequest
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.dto.AdminTrashBatchResponse
import com.soma.wes.admin.resource.dto.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.repository.AdminCascadeTrashRepository
import com.soma.wes.admin.resource.repository.AdminCascadeTrashRepository.BatchRow
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import com.soma.wes.trash.config.TrashProperties
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminCascadeTrashService(
    private val trashRepository: AdminCascadeTrashRepository,
    private val resourceRepository: AdminResourceRepository,
    private val auditService: AdminAuditService,
    private val trashProperties: TrashProperties,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(): List<AdminTrashBatchResponse> = trashRepository.list().map(::response)

    @Transactional
    fun delete(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: ChangeAdminResourceStateRequest,
        sourceAddress: String?,
    ): AdminTrashBatchResponse {
        val before = requireResource(type, id)
        requireVersion(before, request.expectedVersion)
        if (before.deleted || trashRepository.hasOverlappingActiveBatch(type, id)) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }

        val deletedAt = ZonedDateTime.now(clock)
        val batchId = try {
            trashRepository.createBatch(
                actorAdminId = actorAdminId,
                rootType = type,
                rootId = id,
                rootLabel = before.label,
                reason = request.reason.trim(),
                deletedAt = deletedAt,
                restoreUntil = deletedAt.plus(trashProperties.retention),
            )
        } catch (_: DataIntegrityViolationException) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        val counts = trashRepository.moveToTrash(batchId, type, id, deletedAt)
        if (counts[type.name] != 1L) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        if (type == AdminResourceType.USER) {
            trashRepository.revokeRefreshToken(id)
        }

        auditService.recordMutation(
            action = AdminAuditAction.RESOURCE_DELETED,
            actorAdminId = actorAdminId,
            targetType = type.auditTargetType,
            targetId = id.toString(),
            targetLabel = before.label,
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
            before = before.snapshot(),
            after = before.snapshot() + mapOf(
                "version" to before.version + 1,
                "deleted" to true,
                "trashBatchId" to batchId,
                "restoreUntil" to deletedAt.plus(trashProperties.retention),
                "affectedCounts" to counts,
            ),
        )
        return response(requireNotNull(trashRepository.find(batchId)))
    }

    @Transactional
    fun restoreRoot(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: ChangeAdminResourceStateRequest,
        sourceAddress: String?,
    ): AdminResourceResponse {
        val current = requireResource(type, id)
        requireVersion(current, request.expectedVersion)
        val batch = trashRepository.findActiveByRoot(type, id)
        if (batch == null) {
            return restoreLegacy(actorAdminId, current, request.reason.trim(), sourceAddress)
        }
        restore(actorAdminId, batch, request.reason.trim(), sourceAddress)
        return requireResource(type, id)
    }

    @Transactional
    fun restoreBatch(
        actorAdminId: Long,
        batchId: Long,
        request: AdminReasonRequest,
        sourceAddress: String?,
    ): AdminTrashBatchResponse {
        val batch = trashRepository.find(batchId) ?: throw AdminException(AdminErrorCode.TRASH_BATCH_NOT_FOUND)
        restore(actorAdminId, batch, request.reason.trim(), sourceAddress)
        return response(requireNotNull(trashRepository.find(batchId)))
    }

    private fun restore(actorAdminId: Long, batch: BatchRow, reason: String, sourceAddress: String?) {
        if (batch.status == "PURGING" || !ZonedDateTime.now(clock).isBefore(batch.restoreUntil)) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_EXPIRED)
        }
        if (batch.status != "ACTIVE") {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        val expectedCount = trashRepository.affectedCounts(batch.id).values.sum().toInt()
        val restoredCount = trashRepository.restore(batch)
        if (restoredCount != expectedCount || trashRepository.markRestored(batch.id) != 1) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        val after = requireResource(batch.rootType, batch.rootId)
        auditService.recordMutation(
            action = AdminAuditAction.RESOURCE_RESTORED,
            actorAdminId = actorAdminId,
            targetType = batch.rootType.auditTargetType,
            targetId = batch.rootId.toString(),
            targetLabel = batch.rootLabel,
            reason = reason,
            sourceAddress = sourceAddress,
            before = mapOf("deleted" to true, "trashBatchId" to batch.id),
            after = after.snapshot() + mapOf("trashBatchId" to batch.id, "restoredCount" to restoredCount),
        )
    }

    private fun requireResource(type: AdminResourceType, id: Long): AdminResourceResponse =
        resourceRepository.find(type, id) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)

    private fun restoreLegacy(
        actorAdminId: Long,
        before: AdminResourceResponse,
        reason: String,
        sourceAddress: String?,
    ): AdminResourceResponse {
        if (before.type !in LEGACY_TRASH_TYPES || !before.deleted) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_NOT_FOUND)
        }
        if (resourceRepository.restore(before.type, before.id, before.version) != 1) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        val after = requireResource(before.type, before.id)
        auditService.recordMutation(
            action = AdminAuditAction.RESOURCE_RESTORED,
            actorAdminId = actorAdminId,
            targetType = before.type.auditTargetType,
            targetId = before.id.toString(),
            targetLabel = before.label,
            reason = reason,
            sourceAddress = sourceAddress,
            before = before.snapshot(),
            after = after.snapshot(),
        )
        return after
    }

    private fun requireVersion(resource: AdminResourceResponse, expectedVersion: Long) {
        if (resource.version != expectedVersion) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    private fun response(batch: BatchRow): AdminTrashBatchResponse = AdminTrashBatchResponse(
        id = batch.id,
        rootType = batch.rootType,
        rootId = batch.rootId,
        rootLabel = batch.rootLabel,
        status = batch.status,
        actorUsername = batch.actorUsername,
        reason = batch.reason,
        deletedAt = batch.deletedAt,
        restoreUntil = batch.restoreUntil,
        restoredAt = batch.restoredAt,
        purgedAt = batch.purgedAt,
        purgeAttemptCount = batch.purgeAttemptCount,
        failureCode = batch.failureCode,
        affectedCounts = trashRepository.affectedCounts(batch.id),
    )

    private fun AdminResourceResponse.snapshot(): Map<String, Any?> = linkedMapOf(
        "type" to type.name,
        "id" to id,
        "version" to version,
        "label" to label,
        "deleted" to deleted,
    ) + fields

    companion object {
        private val LEGACY_TRASH_TYPES = setOf(AdminResourceType.GALLERY, AdminResourceType.PHOTO)
    }
}
