package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.repository.AdminCascadeTrashRepository
import com.soma.wes.admin.resource.repository.AdminCascadeTrashRepository.BatchRow
import com.soma.wes.photo.service.PhotoStorage
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime

@Service
class AdminCascadeTrashPurgeService(
    private val repository: AdminCascadeTrashRepository,
    private val photoStorage: PhotoStorage,
    private val finalizer: AdminCascadeTrashPurgeFinalizer,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 40 * * * *")
    fun purgeExpired() {
        val now = ZonedDateTime.now(clock)
        while (true) {
            val claimed = repository.claimExpired(now, now.minus(STALE_PURGE_TIMEOUT))
            if (claimed.isEmpty()) return
            claimed.forEach { batch ->
                runCatching {
                    val keys = repository.findPurgeObjectKeys(batch)
                    if (keys.isNotEmpty()) photoStorage.deleteAll(keys)
                    finalizer.finalize(batch)
                }.onFailure { error ->
                    val failedAt = ZonedDateTime.now(clock)
                    repository.markPurgeFailed(
                        batchId = batch.id,
                        failureCode = error::class.simpleName ?: "PURGE_FAILED",
                        nextAttemptAt = failedAt.plus(retryDelay(batch.purgeAttemptCount)),
                        maxAttempts = MAX_PURGE_ATTEMPTS,
                    )
                    log.error(
                        "관리자 연쇄 휴지통 purge 실패, backoff 또는 dead-letter 처리한다: batchId={}",
                        batch.id,
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
class AdminCascadeTrashPurgeFinalizer(
    private val repository: AdminCascadeTrashRepository,
    private val auditService: AdminAuditService,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finalize(claimed: BatchRow) {
        val current = repository.find(claimed.id) ?: throw AdminException(AdminErrorCode.TRASH_BATCH_NOT_FOUND)
        if (current.status != "PURGING") throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        repository.expireRevisionRestorePayloads(current.id)
        repository.deleteOperationalPayloads(current.id)
        if (repository.deleteRoot(current) != 1) throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        if (repository.markPurged(current.id) != 1) throw AdminException(AdminErrorCode.TRASH_BATCH_CONFLICT)
        auditService.recordMutation(
            action = AdminAuditAction.RESOURCE_PURGED,
            actorAdminId = current.actorAdminId,
            targetType = current.rootType.auditTargetType,
            targetId = current.rootId.toString(),
            targetLabel = current.rootLabel,
            reason = "7일 복구 기간 만료 자동 영구 삭제",
            sourceAddress = null,
            before = mapOf("deleted" to true, "trashBatchId" to current.id),
            after = mapOf("purged" to true, "trashBatchId" to current.id),
        )
    }
}
