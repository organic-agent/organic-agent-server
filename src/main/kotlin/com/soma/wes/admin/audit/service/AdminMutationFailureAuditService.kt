package com.soma.wes.admin.audit.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class AdminMutationFailureAuditService(
    private val auditService: AdminAuditService,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        actorAdminId: Long?,
        method: String,
        requestUri: String,
        status: Int,
        sourceAddress: String?,
        correlationId: String?,
    ) {
        recordFailure(AdminAuditAction.MUTATION_FAILED, actorAdminId, method, requestUri, status, sourceAddress, correlationId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordResponseFailure(
        actorAdminId: Long?,
        method: String,
        requestUri: String,
        status: Int,
        sourceAddress: String?,
        correlationId: String?,
    ) {
        recordFailure(AdminAuditAction.RESPONSE_FAILED, actorAdminId, method, requestUri, status, sourceAddress, correlationId)
    }

    private fun recordFailure(
        action: AdminAuditAction,
        actorAdminId: Long?,
        method: String,
        requestUri: String,
        status: Int,
        sourceAddress: String?,
        correlationId: String?,
    ) {
        val target = classify(requestUri)
        auditService.recordMetadataEvent(
            action = action,
            outcome = AdminAuditOutcome.FAILURE,
            actorAdminId = actorAdminId,
            targetType = target.type,
            targetId = target.id,
            targetLabel = null,
            metadata = "route=${if (action == AdminAuditAction.RESPONSE_FAILED) "RESPONSE_FAILURE" else "MUTATION_FAILURE"} " +
                "status=$status method=$method",
            sourceAddress = sourceAddress,
            correlationId = correlationId,
        )
    }

    private fun classify(uri: String): Target {
        RESOURCE.find(uri)?.let { match ->
            val type = runCatching { AdminAuditTargetType.valueOf(match.groupValues[1]) }.getOrNull()
                ?: AdminAuditTargetType.ADMIN_OPERATION
            return Target(type, match.groupValues[2].takeIf(String::isNotBlank))
        }
        TRASH.find(uri)?.let { return Target(AdminAuditTargetType.TRASH_BATCH, it.groupValues[1]) }
        ADMIN_ACCOUNT.find(uri)?.let { return Target(AdminAuditTargetType.ADMIN_ACCOUNT, it.groupValues[1]) }
        IMPERSONATION.find(uri)?.let {
            return Target(AdminAuditTargetType.IMPERSONATION, it.groupValues[1].takeUnless { id -> id == "current" })
        }
        REVISION.find(uri)?.let { return Target(AdminAuditTargetType.ADMIN_OPERATION, "REVISION-${it.groupValues[1]}") }
        return Target(AdminAuditTargetType.ADMIN_OPERATION, null)
    }

    private data class Target(val type: AdminAuditTargetType, val id: String?)

    companion object {
        private val RESOURCE = Regex("/resources/(USER|STUDIO|GALLERY|PHOTO|SELECTION|COLLABORATION|ALBUM|RETOUCH_REQUEST)(?:/([0-9]+))?")
        private val TRASH = Regex("/operations/trash/([0-9]+)")
        private val ADMIN_ACCOUNT = Regex("/admins/([0-9]+)")
        private val IMPERSONATION = Regex("/impersonations/([0-9a-fA-F-]{36}|current)")
        private val REVISION = Regex("/revisions/([0-9]+)")
    }
}
