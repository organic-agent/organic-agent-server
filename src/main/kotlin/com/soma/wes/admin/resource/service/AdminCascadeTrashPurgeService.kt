package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.repository.AdminCascadeTrashRepository
import com.soma.wes.admin.resource.repository.AdminCascadeTrashRepository.BatchRow
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.trash.support.TrashEraser
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
        repository.claimExpired(now, now.minus(STALE_PURGE_TIMEOUT)).forEach { batch ->
            runCatching {
                val objects = repository.findPurgeObjects(batch)
                val keys = objects.photos.flatMapTo(mutableSetOf()) { photo ->
                    listOfNotNull(
                        photo.storageKey,
                        photo.previewKey,
                        TrashEraser.expectedPreviewKeyOf(photo.storageKey),
                    )
                } + objects.retouchKeys
                if (keys.isNotEmpty()) photoStorage.deleteAll(keys)
                finalizer.finalize(batch)
            }.onFailure { error ->
                repository.markPurgeFailed(batch.id, error::class.simpleName ?: "PURGE_FAILED")
                log.error("관리자 연쇄 휴지통 purge 실패, 재시도한다: batchId={}", batch.id, error)
            }
        }
    }

    companion object {
        private val STALE_PURGE_TIMEOUT: Duration = Duration.ofHours(2)
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
