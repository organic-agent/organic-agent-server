package com.soma.wes.admin.audit.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditLog
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.domain.AdminEntityRevision
import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.audit.repository.AdminEntityRevisionRepository
import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import com.soma.wes.admin.audit.support.AdminAuditSanitizer
import com.soma.wes.admin.audit.support.AdminMutationAuditContext
import com.soma.wes.global.filter.HttpLoggingFilter
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.ZonedDateTime
import java.util.UUID

@Service
class AdminAuditService(
    private val auditLogRepository: AdminAuditLogRepository,
    private val revisionRepository: AdminEntityRevisionRepository,
    private val snapshotCodec: AdminAuditSnapshotCodec,
    private val sanitizer: AdminAuditSanitizer,
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
        /** 호환용 인자다. 영구 감사 correlation은 호출자 값이 아니라 현재 MDC trace만 사용한다. */
        correlationId: String? = null,
        impersonationSessionId: UUID? = null,
    ): AdminAuditLog {
        return persistEvent(
            action = action,
            outcome = outcome,
            actorAdminId = actorAdminId,
            actorUsername = actorUsername,
            targetType = targetType,
            targetId = targetId,
            targetLabel = targetLabel,
            storedReason = sanitizer.operatorReason(action, reason),
            sourceAddress = sourceAddress,
            changedFields = changedFields,
            correlationId = correlationId,
            impersonationSessionId = impersonationSessionId,
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun recordMetadataEvent(
        action: AdminAuditAction,
        outcome: AdminAuditOutcome,
        actorAdminId: Long?,
        actorUsername: String? = null,
        targetType: AdminAuditTargetType?,
        targetId: String?,
        targetLabel: String?,
        metadata: String?,
        sourceAddress: String?,
        correlationId: String? = null,
        impersonationSessionId: UUID? = null,
    ): AdminAuditLog = persistEvent(
        action = action,
        outcome = outcome,
        actorAdminId = actorAdminId,
        actorUsername = actorUsername,
        targetType = targetType,
        targetId = targetId,
        targetLabel = targetLabel,
        storedReason = sanitizer.trustedMetadataReason(action, metadata),
        sourceAddress = sourceAddress,
        changedFields = emptyList(),
        correlationId = correlationId,
        impersonationSessionId = impersonationSessionId,
    )

    private fun persistEvent(
        action: AdminAuditAction,
        outcome: AdminAuditOutcome,
        actorAdminId: Long?,
        actorUsername: String?,
        targetType: AdminAuditTargetType?,
        targetId: String?,
        targetLabel: String?,
        storedReason: String,
        sourceAddress: String?,
        changedFields: Collection<String>,
        correlationId: String?,
        impersonationSessionId: UUID?,
    ): AdminAuditLog {
        val sanitizedTargetId = sanitizer.sanitizeTargetId(targetType, targetId)
        val saved = auditLogRepository.saveAndFlush(
            AdminAuditLog.of(
                action = action,
                outcome = outcome,
                actorAdminId = actorAdminId,
                actorUsernameSnapshot = sanitizer.canonicalActorLabel(actorAdminId),
                targetType = targetType,
                targetId = sanitizedTargetId,
                targetLabel = sanitizer.canonicalTargetLabel(targetType, sanitizedTargetId, targetLabel),
                // IP는 운영 로그에서 correlationId로 추적하고 불변 감사 DB에는 영구 보존하지 않는다.
                sourceAddress = null,
                reason = storedReason,
                changedFields = snapshotCodec.sanitizeChangedFields(changedFields),
                correlationId = requestCorrelationId(correlationId),
                impersonationSessionId = impersonationSessionId,
            ),
        )
        if (outcome == AdminAuditOutcome.SUCCESS) markMutationCommittedAfterCommit()
        return saved
    }

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
        /** 호환용 인자다. 영구 감사 correlation은 호출자 값이 아니라 현재 MDC trace만 사용한다. */
        correlationId: String? = null,
        impersonationSessionId: UUID? = null,
    ): AdminAuditLog {
        val sanitizedTargetId = requireNotNull(sanitizer.sanitizeTargetId(targetType, targetId))
        val revisionNumber = revisionRepository.findMaxRevisionNumber(targetType, sanitizedTargetId) + 1
        val changedFields = snapshotCodec.changedFields(before, after)
        val now = ZonedDateTime.now(clock)

        revisionRepository.save(
            AdminEntityRevision.of(
                targetType = targetType,
                targetId = sanitizedTargetId,
                revisionNumber = revisionNumber,
                operation = action,
                beforeSnapshot = snapshotCodec.encode(before),
                afterSnapshot = snapshotCodec.encode(after),
                restoreExpiresAt = now.plusDays(REVISION_RETENTION_DAYS),
                snapshotSchemaVersion = CURRENT_SNAPSHOT_SCHEMA_VERSION,
                targetVersion = snapshotVersion(after) ?: snapshotVersion(before),
                beforeRestorePayload = snapshotCodec.encodeRestorePayload(targetType, before),
                afterRestorePayload = snapshotCodec.encodeRestorePayload(targetType, after),
            ),
        )

        // saveAndFlush가 실패하면 호출자의 비즈니스 변경과 리비전도 같은 트랜잭션에서 롤백된다.
        val saved = auditLogRepository.saveAndFlush(
            AdminAuditLog.of(
                action = action,
                outcome = AdminAuditOutcome.SUCCESS,
                actorAdminId = actorAdminId,
                actorUsernameSnapshot = sanitizer.canonicalActorLabel(actorAdminId),
                targetType = targetType,
                targetId = sanitizedTargetId,
                targetLabel = sanitizer.canonicalTargetLabel(targetType, sanitizedTargetId, targetLabel),
                // IP는 운영 로그에서 correlationId로 추적하고 불변 감사 DB에는 영구 보존하지 않는다.
                sourceAddress = null,
                reason = sanitizer.operatorReason(action, reason),
                changedFields = changedFields,
                revisionNumber = revisionNumber,
                correlationId = requestCorrelationId(correlationId),
                impersonationSessionId = impersonationSessionId,
            ),
        )
        markMutationCommittedAfterCommit()
        return saved
    }

    /**
     * correlationId는 요청 하나를 로그와 연결하는 값이다. 호출자가 세션 UUID나 작업 ID를
     * 넘겨도 저장하지 않으며, HttpLoggingFilter가 현재 요청 MDC에 넣은 16자리 hex만 인정한다.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun requestCorrelationId(callerSuppliedValue: String?): String? =
        sanitizer.sanitizeCorrelationId(MDC.get(HttpLoggingFilter.TRACE_ID_KEY))

    private fun snapshotVersion(snapshot: Map<String, Any?>?): Long? =
        (snapshot?.get("version") as? Number)?.toLong()

    /**
     * 비즈니스 변경과 성공 감사가 들어 있는 실제 트랜잭션이 커밋된 뒤에만 HTTP filter에
     * 커밋 사실을 알린다. 이후 조회나 응답 직렬화가 실패해도 MUTATION_FAILED로 오기록하지 않는다.
     */
    private fun markMutationCommittedAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    AdminMutationAuditContext.markMutationCommitted()
                }
            },
        )
    }

    companion object {
        const val REVISION_RETENTION_DAYS = 7L
        const val CURRENT_SNAPSHOT_SCHEMA_VERSION = 3
    }
}
