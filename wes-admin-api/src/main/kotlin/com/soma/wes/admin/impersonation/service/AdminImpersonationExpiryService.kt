package com.soma.wes.admin.impersonation.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.impersonation.repository.AdminImpersonationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 만료 세션 종료와 END 감사를 별도 트랜잭션으로 함께 확정한다. 호출자가 이후 404를 던져도
 * 이 트랜잭션은 롤백되지 않으며, UPDATE ... RETURNING으로 claim한 세션만 exactly-once 기록한다.
 */
@Service
class AdminImpersonationExpiryService(
    private val repository: AdminImpersonationRepository,
    private val auditService: AdminAuditService,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun closeExpired(adminSessionId: UUID, now: ZonedDateTime): Int {
        val expired = repository.closeExpired(adminSessionId, now)
        expired.forEach { session ->
            auditService.recordMetadataEvent(
                action = AdminAuditAction.READ_ONLY_IMPERSONATION_ENDED,
                outcome = AdminAuditOutcome.SUCCESS,
                actorAdminId = session.adminId,
                targetType = AdminAuditTargetType.valueOf(session.targetType.name),
                targetId = session.targetId.toString(),
                targetLabel = session.targetLabel,
                metadata = "category=READ_ONLY_IMPERSONATION_ENDED end=EXPIRED",
                sourceAddress = null,
                impersonationSessionId = session.id,
            )
        }
        return expired.size
    }
}
