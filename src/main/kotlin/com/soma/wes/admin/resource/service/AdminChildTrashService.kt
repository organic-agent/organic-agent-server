package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.audit.support.AdminAuditSanitizer
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminChildTrashResponse
import com.soma.wes.admin.resource.repository.AdminChildTrashRepository
import com.soma.wes.admin.resource.repository.AdminChildTrashRepository.ChildTrashRow
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.trash.config.TrashProperties
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime

@Service
class AdminChildTrashService(
    private val repository: AdminChildTrashRepository,
    private val sanitizer: AdminAuditSanitizer,
    private val trashProperties: TrashProperties,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(): List<AdminChildTrashResponse> = repository.list().map(::response)

    @Transactional
    fun delete(
        actorAdminId: Long,
        type: AdminChildTrashType,
        resourceId: Long,
        parentId: Long,
        expectedParentVersion: Long,
        expectedChildVersion: Long?,
        reason: String,
    ): AdminChildTrashResult {
        repository.lockProductPurgeCoordination(type, resourceId, parentId)
        val parent = repository.lockParent(type, parentId)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        if (
            parent.deleted ||
            repository.isCascadeBlocked(type, resourceId, parentId) ||
            repository.isProductPurgeClaimBlocked(type, resourceId, parentId)
        ) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        if (parent.version != expectedParentVersion) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        if (repository.findActiveForUpdate(type, resourceId) != null) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }

        val deletedAt = ZonedDateTime.now(clock)
        val updated = repository.softDeleteChild(
            type = type,
            resourceId = resourceId,
            parentId = parentId,
            expectedChildVersion = expectedChildVersion,
            deletedAt = deletedAt,
        )
        if (updated != 1) {
            throw AdminException(
                if (expectedChildVersion == null) AdminErrorCode.RESOURCE_NOT_FOUND
                else AdminErrorCode.RESOURCE_VERSION_CONFLICT,
            )
        }
        if (repository.bumpParentVersion(type, parentId, expectedParentVersion) != 1) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }

        val sanitizedReason = sanitizer.canonicalOperatorReason(reason)
        val restoreUntil = deletedAt.plus(trashProperties.retention)
        val id = try {
            repository.create(
                type = type,
                resourceId = resourceId,
                parentId = parentId,
                actorAdminId = actorAdminId,
                reason = sanitizedReason,
                deletedAt = deletedAt,
                restoreUntil = restoreUntil,
            )
        } catch (_: DataIntegrityViolationException) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        return result(
            ChildTrashRow(
                id = id,
                type = type,
                resourceId = resourceId,
                parentType = type.parentType,
                parentId = parentId,
                actorAdminId = actorAdminId,
                actorUsername = null,
                reason = sanitizedReason,
                status = "ACTIVE",
                deletedAt = deletedAt,
                restoreUntil = restoreUntil,
                restoredAt = null,
                purgedAt = null,
                purgeAttemptCount = 0,
                purgeStartedAt = null,
                failureCode = null,
            ),
        )
    }

    @Transactional
    fun restore(
        type: AdminChildTrashType,
        resourceId: Long,
        parentId: Long,
        expectedParentVersion: Long,
        expectedChildVersion: Long?,
    ): AdminChildTrashResult {
        // delete와 같은 순서(coordination -> parent -> trash row)로 잠가 상호 교착을 피한다.
        repository.lockProductPurgeCoordination(type, resourceId, parentId)
        val parent = repository.lockParent(type, parentId)
        val row = repository.findActiveForUpdate(type, resourceId)
        if (row == null) {
            if (
                repository.isCascadeBlocked(type, resourceId, parentId) ||
                repository.isProductPurgeClaimBlocked(type, resourceId, parentId)
            ) {
                throw AdminException(AdminErrorCode.TRASH_CHILD_RESTORE_FORBIDDEN)
            }
            throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        }
        if (row.parentType != type.parentType || row.parentId != parentId) {
            throw AdminException(AdminErrorCode.TRASH_CHILD_RESTORE_FORBIDDEN)
        }
        val now = ZonedDateTime.now(clock)
        if (row.status == "PURGING" || !now.isBefore(row.restoreUntil)) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_EXPIRED)
        }

        if (
            parent == null ||
            parent.deleted ||
            repository.isCascadeBlocked(type, resourceId, parentId) ||
            repository.isProductPurgeClaimBlocked(type, resourceId, parentId)
        ) {
            throw AdminException(AdminErrorCode.TRASH_CHILD_RESTORE_FORBIDDEN)
        }
        if (parent.version != expectedParentVersion) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        // 하객의 제품 좋아요 생성과 동일한 collab_photo 잠금을 잡은 뒤 검사와 복원을 이어간다.
        if (!repository.lockCollabPhotoLikeIdentity(row)) {
            throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        }
        if (repository.hasRestoreIdentityConflict(row)) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        val restored = try {
            repository.restoreChild(row, expectedChildVersion)
        } catch (_: DataIntegrityViolationException) {
            // 잠금을 사용하지 않는 과거/외부 writer가 있더라도 partial unique 위반을 500으로
            // 노출하지 않고, 새 active 반응을 보존하는 일관된 409 도메인 충돌로 바꾼다.
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        if (restored != 1) {
            throw AdminException(
                if (expectedChildVersion == null) AdminErrorCode.RESOURCE_NOT_FOUND
                else AdminErrorCode.RESOURCE_VERSION_CONFLICT,
            )
        }
        if (repository.bumpParentVersion(type, parentId, expectedParentVersion) != 1) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        if (repository.markRestored(row.id, now) != 1) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        return result(row.copy(status = "RESTORED", restoredAt = now))
    }

    private fun result(row: ChildTrashRow): AdminChildTrashResult = AdminChildTrashResult(
        trashId = row.id,
        resourceType = row.type,
        resourceId = row.resourceId,
        parentType = row.parentType,
        parentId = row.parentId,
        status = row.status,
        deletedAt = row.deletedAt,
        restoreUntil = row.restoreUntil,
        purgeEligibleAt = row.restoreUntil,
        restoreWindowDays = trashProperties.retention.toDays(),
        restorable = row.status == "ACTIVE" && ZonedDateTime.now(clock).isBefore(row.restoreUntil),
    )

    private fun response(row: ChildTrashRow): AdminChildTrashResponse = AdminChildTrashResponse(
        id = row.id,
        resourceType = row.type,
        resourceId = row.resourceId,
        parentType = row.parentType,
        parentId = row.parentId,
        status = row.status,
        actorUsername = row.actorUsername,
        reason = row.reason,
        deletedAt = row.deletedAt,
        restoreUntil = row.restoreUntil,
        purgeEligibleAt = row.restoreUntil,
        restoreWindowDays = trashProperties.retention.toDays(),
        restorable = row.status == "ACTIVE" && ZonedDateTime.now(clock).isBefore(row.restoreUntil),
        restoredAt = row.restoredAt,
        purgedAt = row.purgedAt,
        purgeAttemptCount = row.purgeAttemptCount,
        failureCode = row.failureCode,
    )
}

data class AdminChildTrashResult(
    val trashId: Long,
    val resourceType: AdminChildTrashType,
    val resourceId: Long,
    val parentType: AdminResourceType,
    val parentId: Long,
    val status: String,
    val deletedAt: ZonedDateTime,
    val restoreUntil: ZonedDateTime,
    val purgeEligibleAt: ZonedDateTime,
    val restoreWindowDays: Long,
    val restorable: Boolean,
) {
    fun workflowDetails(): Map<String, Any?> = linkedMapOf(
        "childTrashId" to trashId,
        "resourceType" to resourceType.name,
        "resourceId" to resourceId,
        "parentType" to parentType.name,
        "parentId" to parentId,
        "trashStatus" to status,
        "deletedAt" to deletedAt,
        "restoreUntil" to restoreUntil,
        "purgeEligibleAt" to purgeEligibleAt,
        "restoreWindowDays" to restoreWindowDays,
        "restorable" to restorable,
    )
}

@Service
class AdminChildTrashPurgeService(
    private val repository: AdminChildTrashRepository,
    private val photoStorage: PhotoStorage,
    private val finalizer: AdminChildTrashPurgeFinalizer,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 45 * * * *")
    fun purgeExpired() {
        val now = ZonedDateTime.now(clock)
        while (true) {
            val claimed = repository.claimExpired(now, now.minus(STALE_PURGE_TIMEOUT))
            if (claimed.isEmpty()) return
            claimed.forEach { row ->
                runCatching {
                    val storageKeys = repository.storageKeys(row)
                    if (storageKeys.isNotEmpty()) photoStorage.deleteAll(storageKeys)
                    finalizer.finalize(row)
                }.onFailure { error ->
                    val failedAt = ZonedDateTime.now(clock)
                    repository.markPurgeFailed(
                        id = row.id,
                        failureCode = error::class.simpleName ?: "PURGE_FAILED",
                        nextAttemptAt = failedAt.plus(retryDelay(row.purgeAttemptCount)),
                        maxAttempts = MAX_PURGE_ATTEMPTS,
                    )
                    log.error(
                        "관리자 자식 휴지통 purge 실패, backoff 또는 dead-letter 처리한다: childTrashId={}",
                        row.id,
                        error,
                    )
                }
            }
        }
    }

    private fun retryDelay(attempt: Int): Duration {
        val exponent = (attempt - 1).coerceIn(0, MAX_PURGE_ATTEMPTS - 2)
        return INITIAL_RETRY_DELAY.multipliedBy(1L shl exponent)
    }

    companion object {
        private val STALE_PURGE_TIMEOUT: Duration = Duration.ofHours(2)
        private val INITIAL_RETRY_DELAY: Duration = Duration.ofMinutes(5)
        private const val MAX_PURGE_ATTEMPTS: Int = 5
    }
}

@Service
class AdminChildTrashPurgeFinalizer(
    private val repository: AdminChildTrashRepository,
    private val auditService: AdminAuditService,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finalize(claimed: ChildTrashRow) {
        val current = repository.findForUpdate(claimed.id)
            ?: throw AdminException(AdminErrorCode.TRASH_BATCH_NOT_FOUND)
        if (current.status != "PURGING") throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        val deleted = repository.deleteChild(current)
        if (deleted != 1 && repository.childExists(current)) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        val purgedAt = ZonedDateTime.now(clock)
        if (repository.markPurged(current.id, purgedAt) != 1) {
            throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        auditService.recordMutation(
            action = AdminAuditAction.RESOURCE_PURGED,
            actorAdminId = current.actorAdminId,
            targetType = AdminAuditTargetType.TRASH_BATCH,
            // cascade batch id와 child trash id는 서로 다른 sequence다. 접두어가 없으면
            // 같은 TRASH_BATCH 감사 대상 revision으로 합쳐질 수 있다.
            targetId = "CHILD-${current.id}",
            targetLabel = null,
            reason = "7일 복구 기간 만료 자동 영구 삭제",
            sourceAddress = null,
            before = mapOf(
                "childType" to current.type.name,
                "childId" to current.resourceId,
                "parentType" to current.parentType.name,
                "parentId" to current.parentId,
                "deleted" to true,
            ),
            after = mapOf(
                "childType" to current.type.name,
                "childId" to current.resourceId,
                "parentType" to current.parentType.name,
                "parentId" to current.parentId,
                "purged" to true,
            ),
        )
    }
}
