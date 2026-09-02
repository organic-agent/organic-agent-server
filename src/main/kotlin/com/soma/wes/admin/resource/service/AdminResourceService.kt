package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminResourcePageResponse
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.dto.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.UpdateAdminResourceRequest
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminResourceService(
    private val repository: AdminResourceRepository,
    private val auditService: AdminAuditService,
) {

    @Transactional(readOnly = true)
    fun search(
        query: String?,
        types: Set<AdminResourceType>,
        page: Int,
        size: Int,
    ): AdminResourcePageResponse {
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        return repository.search(query, types, page, size)
    }

    @Transactional(readOnly = true)
    fun get(type: AdminResourceType, id: Long): AdminResourceResponse = requireResource(type, id)

    @Transactional
    fun create(
        actorAdminId: Long,
        type: AdminResourceType,
        request: CreateAdminResourceRequest,
        sourceAddress: String?,
    ): AdminResourceResponse = translateIntegrityFailure {
        val creation = repository.create(type, request.fields)
        val created = requireResource(type, creation.id)
        auditService.recordMutation(
            action = AdminAuditAction.RESOURCE_CREATED,
            actorAdminId = actorAdminId,
            targetType = type.auditTargetType,
            targetId = creation.id.toString(),
            targetLabel = created.label,
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
            before = null,
            after = created.snapshot(),
        )
        created
    }

    @Transactional
    fun update(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: UpdateAdminResourceRequest,
        sourceAddress: String?,
    ): AdminResourceResponse = translateIntegrityFailure {
        val before = requireResource(type, id)
        requireVersion(before, request.expectedVersion)
        if (repository.update(type, id, request.expectedVersion, request.fields) == 0) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        val after = requireResource(type, id)
        auditService.recordMutation(
            action = AdminAuditAction.RESOURCE_UPDATED,
            actorAdminId = actorAdminId,
            targetType = type.auditTargetType,
            targetId = id.toString(),
            targetLabel = after.label,
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
            before = before.snapshot(),
            after = after.snapshot(),
        )
        after
    }

    @Transactional
    fun delete(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: ChangeAdminResourceStateRequest,
        sourceAddress: String?,
    ) = translateIntegrityFailure {
        val before = requireResource(type, id)
        requireVersion(before, request.expectedVersion)
        if (repository.delete(type, id, request.expectedVersion) == 0) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        val after = repository.find(type, id)
        auditService.recordMutation(
            action = AdminAuditAction.RESOURCE_DELETED,
            actorAdminId = actorAdminId,
            targetType = type.auditTargetType,
            targetId = id.toString(),
            targetLabel = before.label,
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
            before = before.snapshot(),
            after = after?.snapshot(),
        )
    }

    @Transactional
    fun restore(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        request: ChangeAdminResourceStateRequest,
        sourceAddress: String?,
    ): AdminResourceResponse = translateIntegrityFailure {
        val before = requireResource(type, id)
        requireVersion(before, request.expectedVersion)
        if (!before.deleted) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        if (repository.restore(type, id, request.expectedVersion) == 0) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        val after = requireResource(type, id)
        auditService.recordMutation(
            action = AdminAuditAction.RESOURCE_RESTORED,
            actorAdminId = actorAdminId,
            targetType = type.auditTargetType,
            targetId = id.toString(),
            targetLabel = after.label,
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
            before = before.snapshot(),
            after = after.snapshot(),
        )
        after
    }

    private fun requireResource(type: AdminResourceType, id: Long): AdminResourceResponse =
        repository.find(type, id) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)

    private fun requireVersion(resource: AdminResourceResponse, expectedVersion: Long) {
        if (resource.version != expectedVersion) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
    }

    private fun AdminResourceResponse.snapshot(): Map<String, Any?> =
        linkedMapOf(
            "type" to type.name,
            "id" to id,
            "version" to version,
            "label" to label,
            "deleted" to deleted,
        ) + fields

    private inline fun <T> translateIntegrityFailure(block: () -> T): T =
        try {
            block()
        } catch (_: DataIntegrityViolationException) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

    companion object {
        private const val MAX_PAGE_SIZE = 100
    }
}
