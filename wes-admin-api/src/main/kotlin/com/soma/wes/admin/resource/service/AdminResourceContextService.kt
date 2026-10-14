package com.soma.wes.admin.resource.service

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminResourceContextResponse
import com.soma.wes.admin.resource.dto.AdminResourceSummaryResponse
import com.soma.wes.admin.resource.repository.AdminResourceContextRepository
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import com.soma.wes.admin.resource.repository.AdminCascadeTrashRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminResourceContextService(
    private val resourceRepository: AdminResourceRepository,
    private val contextRepository: AdminResourceContextRepository,
    private val trashRepository: AdminCascadeTrashRepository,
    private val clock: Clock,
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
        val facts = contextRepository.findFacts(type, id) + restoreFacts(type, id)
        val sections = contextRepository.findSections(type, id)
        return AdminResourceContextResponse(
            resource = resource,
            relations = relations,
            facts = facts,
            sections = sections,
            sectionPageInfo = emptyMap(),
        )
    }

    private fun restoreFacts(type: AdminResourceType, id: Long): Map<String, Any?> {
        val rootBatch = trashRepository.findActiveByRoot(type, id)
        val activeBatch = rootBatch ?: trashRepository.findActiveContaining(type, id)
        return linkedMapOf(
            "trashBatchId" to activeBatch?.id,
            "canRestoreDirectly" to (
                rootBatch?.status == "ACTIVE" && ZonedDateTime.now(clock).isBefore(rootBatch.restoreUntil)
            ),
        )
    }

}
