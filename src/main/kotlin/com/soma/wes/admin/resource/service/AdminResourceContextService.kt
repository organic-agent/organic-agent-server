package com.soma.wes.admin.resource.service

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminResourceContextResponse
import com.soma.wes.admin.resource.dto.AdminResourceSectionPageInfo
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
        val facts = contextRepository.findFacts(type, id)
        val sections = contextRepository.findSections(type, id)
        return AdminResourceContextResponse(
            resource = resource,
            relations = relations,
            facts = facts,
            sections = sections,
            sectionPageInfo = sectionPageInfo(type, facts, sections),
        )
    }

    /**
     * 앨범 컨텍스트는 운영 화면의 안전 상한까지만 싣되 실제 합계와 절단 여부를 함께 준다.
     * 클라이언트가 일부 결과를 전체인 것처럼 표시하지 않게 하는 응답 계약이다.
     */
    private fun sectionPageInfo(
        type: AdminResourceType,
        facts: Map<String, Any?>,
        sections: Map<String, List<Map<String, Any?>>>,
    ): Map<String, AdminResourceSectionPageInfo> {
        if (type != AdminResourceType.ALBUM) return emptyMap()
        return linkedMapOf(
            "folders" to pageInfo((facts["folders"] as? Number)?.toLong(), sections["folders"]),
            "items" to pageInfo((facts["photos"] as? Number)?.toLong(), sections["items"]),
        )
    }

    private fun pageInfo(total: Long?, rows: List<Map<String, Any?>>?): AdminResourceSectionPageInfo {
        val returned = rows.orEmpty().size
        val totalCount = total ?: returned.toLong()
        return AdminResourceSectionPageInfo(
            totalCount = totalCount,
            returnedCount = returned,
            truncated = totalCount > returned,
        )
    }
}
