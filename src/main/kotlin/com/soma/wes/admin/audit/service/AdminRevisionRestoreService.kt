package com.soma.wes.admin.audit.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.repository.AdminEntityRevisionRepository
import com.soma.wes.admin.audit.support.AdminAccountAuditSnapshot
import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import com.soma.wes.admin.dto.request.AdminReasonRequest
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.service.AdminSessionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminRevisionRestoreService(
    private val revisionRepository: AdminEntityRevisionRepository,
    private val adminAccountRepository: AdminAccountRepository,
    private val adminSessionService: AdminSessionService,
    private val auditService: AdminAuditService,
    private val snapshotCodec: AdminAuditSnapshotCodec,
    private val clock: Clock,
) {

    @Transactional
    fun restore(
        actorAdminId: Long,
        revisionId: Long,
        request: AdminReasonRequest,
        sourceAddress: String?,
    ): Long {
        validateReason(request.reason)
        val revision = revisionRepository.findById(revisionId).orElseThrow {
            AdminException(AdminErrorCode.REVISION_NOT_FOUND)
        }
        if (!revision.expiresAt.isAfter(ZonedDateTime.now(clock))) {
            throw AdminException(AdminErrorCode.REVISION_EXPIRED)
        }
        if (revision.targetType != AdminAuditTargetType.ADMIN_ACCOUNT) {
            throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        }

        val targetAdminId = revision.targetId.toLongOrNull()
            ?: throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        val account = adminAccountRepository.findWithLockById(targetAdminId)
            ?: throw AdminException(AdminErrorCode.ACCOUNT_NOT_FOUND)
        val restoreNode = snapshotCodec.decode(revision.afterSnapshot)
            ?: throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        val restoreSnapshot = AdminAccountAuditSnapshot.from(restoreNode)
        val before = AdminAccountAuditSnapshot.from(account).toMap()

        account.restoreAuditableState(
            displayName = restoreSnapshot.displayName,
            status = restoreSnapshot.status,
            failedLoginAttempts = restoreSnapshot.failedLoginAttempts,
            lockedUntil = restoreSnapshot.lockedUntil,
        )
        if (account.isSuspended() || account.isLocked(ZonedDateTime.now(clock))) {
            adminSessionService.revokeAll(account.requiredId)
        }

        val audit = auditService.recordMutation(
            action = AdminAuditAction.REVISION_RESTORED,
            actorAdminId = actorAdminId,
            targetType = AdminAuditTargetType.ADMIN_ACCOUNT,
            targetId = account.requiredId.toString(),
            targetLabel = account.username,
            reason = request.reason,
            sourceAddress = sourceAddress,
            before = before,
            after = AdminAccountAuditSnapshot.from(account).toMap(),
        )
        return audit.requiredId
    }

    private fun validateReason(reason: String) {
        if (reason.isBlank() || reason.trim().length > 500) {
            throw AdminException(AdminErrorCode.INVALID_REASON)
        }
    }
}
