package com.soma.wes.admin.resource.service

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
) {
    companion object {
        private val SUPPORTED_TYPES = setOf(AdminResourceType.USER, AdminResourceType.STUDIO)
    }

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
        if (suspended) repository.affectedUserIds(type, id).forEach(repository::revokeRefreshToken)
        val after = repository.find(type, id) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        return after
    }
}
