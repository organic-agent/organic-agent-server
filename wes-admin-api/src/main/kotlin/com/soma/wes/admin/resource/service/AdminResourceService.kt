package com.soma.wes.admin.resource.service

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.response.AdminResourcePageResponse
import com.soma.wes.admin.resource.dto.response.AdminResourceResponse
import com.soma.wes.admin.resource.dto.request.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.request.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.request.UpdateAdminResourceRequest
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import com.soma.wes.admin.support.AdminPhotoRestoreQuota
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminResourceService(
    private val repository: AdminResourceRepository,
    private val photoRestoreQuota: AdminPhotoRestoreQuota,
) {

    companion object {
        private const val MAX_PAGE_SIZE = 100
    }

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
        photoRestoreQuota.requireCapacity(type = type, id = id)
        if (repository.restore(type, id, request.expectedVersion) == 0) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        val after = requireResource(type, id)
        after
    }

    private fun requireResource(type: AdminResourceType, id: Long): AdminResourceResponse =
        repository.find(type, id) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)

    private fun requireVersion(resource: AdminResourceResponse, expectedVersion: Long) {
        if (resource.version != expectedVersion) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
    }

    private inline fun <T> translateIntegrityFailure(block: () -> T): T =
        try {
            block()
        } catch (_: DataIntegrityViolationException) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
}
