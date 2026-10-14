package com.soma.wes.admin.audit.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.dto.request.AdminRevisionRestoreRequest
import com.soma.wes.admin.audit.repository.AdminEntityRevisionRepository
import com.soma.wes.admin.audit.support.AdminAccountAuditSnapshot
import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import com.soma.wes.admin.service.AdminSessionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminRevisionRestoreService(
    private val revisionRepository: AdminEntityRevisionRepository,
    private val adminAccountRepository: AdminAccountRepository,
    private val resourceRepository: AdminResourceRepository,
    private val adminSessionService: AdminSessionService,
    private val auditService: AdminAuditService,
    private val snapshotCodec: AdminAuditSnapshotCodec,
    private val restorePolicy: AdminRevisionRestorePolicy,
    private val clock: Clock,
) {

    @Transactional
    fun restore(
        actorAdminId: Long,
        revisionId: Long,
        request: AdminRevisionRestoreRequest,
        sourceAddress: String?,
    ): Long {
        val revision = revisionRepository.findById(revisionId).orElseThrow {
            AdminException(AdminErrorCode.REVISION_NOT_FOUND)
        }
        if (!revision.restoreExpiresAt.isAfter(ZonedDateTime.now(clock))) {
            throw AdminException(AdminErrorCode.REVISION_EXPIRED)
        }
        if (revision.snapshotSchemaVersion != AdminAuditService.CURRENT_SNAPSHOT_SCHEMA_VERSION) {
            throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        }
        if (!restorePolicy.isRestorable(revision)) {
            throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        }
        // schema v3는 API 미노출 단기 payload가 반드시 있어야 한다. 마스킹된 영구 snapshot으로
        // 부분 복원을 시도하면 일부 필드만 조용히 건너뛸 수 있으므로 fail-closed 한다.
        val restoreNode = snapshotCodec.decode(revision.afterRestorePayload)
            ?: throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        validateSnapshotVersion(revision.targetVersion, restoreNode)

        return if (revision.targetType == AdminAuditTargetType.ADMIN_ACCOUNT) {
            restoreAdminAccount(actorAdminId, revision.targetId, request, sourceAddress, restoreNode)
        } else {
            val type = revision.targetType.toResourceType()
            restoreResource(actorAdminId, type, revision.targetId, request, sourceAddress, restoreNode, revisionId)
        }
    }

    private fun restoreAdminAccount(
        actorAdminId: Long,
        targetId: String,
        request: AdminRevisionRestoreRequest,
        sourceAddress: String?,
        restoreNode: JsonNode,
    ): Long {
        val targetAdminId = targetId.toLongOrNull()
            ?: throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        val account = adminAccountRepository.findWithLockById(targetAdminId)
            ?: throw AdminException(AdminErrorCode.ACCOUNT_NOT_FOUND)
        if (account.version != request.expectedVersion) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        val restoreSnapshot = runCatching { AdminAccountAuditSnapshot.from(restoreNode) }
            .getOrElse { throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED) }
        if (restoreSnapshot.username != REDACTED && restoreSnapshot.username != account.username) {
            throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        }
        val before = AdminAccountAuditSnapshot.from(account).toMap()
        account.restoreAuditableState(
            displayName = restoreSnapshot.displayName.takeUnless { it == REDACTED } ?: account.displayName,
            status = restoreSnapshot.status,
            failedLoginAttempts = restoreSnapshot.failedLoginAttempts,
            lockedUntil = restoreSnapshot.lockedUntil,
        )
        adminAccountRepository.flush()
        if (account.isSuspended() || account.isLocked(ZonedDateTime.now(clock))) {
            adminSessionService.revokeAll(account.requiredId)
        }
        val after = AdminAccountAuditSnapshot.from(account).toMap()
        return auditService.recordMutation(
            action = AdminAuditAction.REVISION_RESTORED,
            actorAdminId = actorAdminId,
            targetType = AdminAuditTargetType.ADMIN_ACCOUNT,
            targetId = account.requiredId.toString(),
            targetLabel = account.username,
            reason = request.reason,
            sourceAddress = sourceAddress,
            before = before,
            after = after + mapOf("restoredSnapshotVersion" to AdminAuditService.CURRENT_SNAPSHOT_SCHEMA_VERSION),
        ).requiredId
    }

    private fun restoreResource(
        actorAdminId: Long,
        type: AdminResourceType,
        targetId: String,
        request: AdminRevisionRestoreRequest,
        sourceAddress: String?,
        restoreNode: JsonNode,
        selectedRevisionId: Long,
    ): Long {
        val id = targetId.toLongOrNull()
            ?: throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        val restored = resourceRepository.restoreRevision(type, id, request.expectedVersion, restoreNode)
        return auditService.recordMutation(
            action = AdminAuditAction.REVISION_RESTORED,
            actorAdminId = actorAdminId,
            targetType = type.auditTargetType,
            targetId = id.toString(),
            targetLabel = restored.after.label,
            reason = request.reason,
            sourceAddress = sourceAddress,
            before = restored.before.snapshot(),
            after = restored.after.snapshot() + mapOf(
                "selectedRevisionId" to selectedRevisionId,
                "restoredSnapshotVersion" to AdminAuditService.CURRENT_SNAPSHOT_SCHEMA_VERSION,
            ),
        ).requiredId
    }

    private fun validateSnapshotVersion(targetVersion: Long?, node: JsonNode) {
        val snapshotVersion = node.path("version")
        if (!snapshotVersion.isIntegralNumber || targetVersion == null || snapshotVersion.asLong() != targetVersion) {
            throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        }
    }

    private fun AdminAuditTargetType.toResourceType(): AdminResourceType =
        runCatching { AdminResourceType.valueOf(name) }
            .getOrElse { throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED) }

    private fun AdminResourceResponse.snapshot(): Map<String, Any?> = linkedMapOf(
        "type" to type.name,
        "id" to id,
        "version" to version,
        "label" to label,
        "deleted" to deleted,
    ) + fields

    companion object {
        private const val REDACTED = "[REDACTED]"
    }
}
