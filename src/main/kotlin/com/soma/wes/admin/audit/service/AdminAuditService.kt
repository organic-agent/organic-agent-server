package com.soma.wes.admin.audit.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditLog
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.domain.AdminEntityRevision
import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.audit.repository.AdminEntityRevisionRepository
import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import com.soma.wes.admin.repository.AdminAccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminAuditService(
    private val auditLogRepository: AdminAuditLogRepository,
    private val revisionRepository: AdminEntityRevisionRepository,
    private val adminAccountRepository: AdminAccountRepository,
    private val snapshotCodec: AdminAuditSnapshotCodec,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun recordEvent(
        action: AdminAuditAction,
        outcome: AdminAuditOutcome,
        actorAdminId: Long?,
        actorUsername: String? = null,
        targetType: AdminAuditTargetType?,
        targetId: String?,
        targetLabel: String?,
        reason: String?,
        sourceAddress: String?,
        changedFields: Collection<String> = emptyList(),
    ): AdminAuditLog =
        auditLogRepository.saveAndFlush(
            AdminAuditLog.of(
                action = action,
                outcome = outcome,
                actorAdminId = actorAdminId,
                actorUsernameSnapshot = actorUsername ?: resolveActorUsername(actorAdminId),
                targetType = targetType,
                targetId = targetId,
                targetLabel = targetLabel,
                sourceAddress = sourceAddress,
                reason = reason,
                changedFields = changedFields,
            ),
        )

    @Transactional(propagation = Propagation.MANDATORY)
    fun recordMutation(
        action: AdminAuditAction,
        actorAdminId: Long?,
        targetType: AdminAuditTargetType,
        targetId: String,
        targetLabel: String?,
        reason: String?,
        sourceAddress: String?,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
    ): AdminAuditLog {
        val revisionNumber = revisionRepository.findMaxRevisionNumber(targetType, targetId) + 1
        val changedFields = snapshotCodec.changedFields(before, after)
        val now = ZonedDateTime.now(clock)

        revisionRepository.save(
            AdminEntityRevision.of(
                targetType = targetType,
                targetId = targetId,
                revisionNumber = revisionNumber,
                operation = action,
                beforeSnapshot = snapshotCodec.encode(before),
                afterSnapshot = snapshotCodec.encode(after),
                expiresAt = now.plusDays(REVISION_RETENTION_DAYS),
            ),
        )

        // saveAndFlush가 실패하면 호출자의 비즈니스 변경과 리비전도 같은 트랜잭션에서 롤백된다.
        return auditLogRepository.saveAndFlush(
            AdminAuditLog.of(
                action = action,
                outcome = AdminAuditOutcome.SUCCESS,
                actorAdminId = actorAdminId,
                actorUsernameSnapshot = resolveActorUsername(actorAdminId),
                targetType = targetType,
                targetId = targetId,
                targetLabel = targetLabel,
                sourceAddress = sourceAddress,
                reason = reason,
                changedFields = changedFields,
                revisionNumber = revisionNumber,
            ),
        )
    }

    private fun resolveActorUsername(actorAdminId: Long?): String? =
        actorAdminId?.let { adminAccountRepository.findById(it).orElse(null)?.username }

    companion object {
        const val REVISION_RETENTION_DAYS = 7L
    }
}
