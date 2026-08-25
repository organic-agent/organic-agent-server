package com.soma.wes.admin.resource.service

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminResourceContextResponse
import com.soma.wes.admin.resource.dto.AdminResourceSummaryResponse
import com.soma.wes.admin.resource.repository.AdminResourceContextRepository
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminResourceContextService(
    private val resourceRepository: AdminResourceRepository,
    private val contextRepository: AdminResourceContextRepository,
) {

    @Transactional(readOnly = true)
    fun get(type: AdminResourceType, id: Long): AdminResourceContextResponse {
        val resource = resourceRepository.find(type, id)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        val relations = contextRepository.findRelations(type, id)
            .mapNotNull { reference -> resourceRepository.find(reference.type, reference.id) }
            .map { related ->
                AdminResourceSummaryResponse(
                    type = related.type,
                    id = related.id,
                    version = related.version,
                    label = related.label,
                    deleted = related.deleted,
                    createdAt = related.createdAt,
                    updatedAt = related.updatedAt,
                )
            }
        return AdminResourceContextResponse(
            resource = resource,
            relations = relations,
            facts = contextRepository.findFacts(type, id),
        )
    }
}
