package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.service.AdminAuditQueryService
import com.soma.wes.admin.resource.dto.AdminOperationsOverviewResponse
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminOperationsOverviewService(
    private val resourceRepository: AdminResourceRepository,
    private val auditQueryService: AdminAuditQueryService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun get(): AdminOperationsOverviewResponse = AdminOperationsOverviewResponse(
        resourceCounts = resourceRepository.countAll(),
        operationalIssues = resourceRepository.findOperationalIssues(),
        trashPendingCount = resourceRepository.countTrashPending(),
        recentAudits = auditQueryService.search(
            actorAdminId = null,
            actorUsername = null,
            targetType = null,
            targetId = null,
            action = null,
            outcome = null,
            from = null,
            to = null,
            page = 0,
            size = 8,
        ).contents,
        generatedAt = ZonedDateTime.now(clock),
    )
}
