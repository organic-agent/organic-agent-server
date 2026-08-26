package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.dto.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminResourceSuspensionService(
    private val repository: AdminResourceRepository,
    private val auditService: AdminAuditService,
) {
    @Transactional
    fun suspend(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: ChangeAdminResourceStateRequest,
        sourceAddress: String?,
    ): AdminResourceResponse = change(actorAdminId, type, id, request, sourceAddress, true)

    @Transactional
    fun activate(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: ChangeAdminResourceStateRequest,
        sourceAddress: String?,
    ): AdminResourceResponse = change(actorAdminId, type, id, request, sourceAddress, false)

    private fun change(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: ChangeAdminResourceStateRequest,
        sourceAddress: String?,
        suspended: Boolean,
    ): AdminResourceResponse {
        if (type !in SUPPORTED_TYPES) throw AdminException(AdminErrorCode.RESOURCE_SUSPENSION_UNSUPPORTED)
        val before = repository.find(type, id) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        if (before.deleted) throw AdminException(AdminErrorCode.RESOURCE_SUSPENSION_CONFLICT)
        if (before.version != request.expectedVersion) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        val alreadySuspended = before.fields["suspendedAt"] != null
        if (alreadySuspended == suspended) throw AdminException(AdminErrorCode.RESOURCE_SUSPENSION_CONFLICT)
        if (repository.setSuspended(type, id, request.expectedVersion, suspended) != 1) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        val ownerUserId = repository.ownerUserId(type, id)
        if (suspended) repository.revokeRefreshToken(ownerUserId)
        val after = repository.find(type, id) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        auditService.recordMutation(
            action = if (suspended) AdminAuditAction.RESOURCE_SUSPENDED else AdminAuditAction.RESOURCE_ACTIVATED,
            actorAdminId = actorAdminId,
            targetType = type.auditTargetType,
            targetId = id.toString(),
            targetLabel = after.label,
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
            before = before.snapshot(),
            after = after.snapshot(),
        )
        return after
    }

    private fun AdminResourceResponse.snapshot(): Map<String, Any?> = linkedMapOf(
        "type" to type.name,
        "id" to id,
        "version" to version,
        "label" to label,
        "deleted" to deleted,
    ) + fields

    companion object {
        private val SUPPORTED_TYPES = setOf(AdminResourceType.USER, AdminResourceType.STUDIO)
    }
}
