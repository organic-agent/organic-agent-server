package com.soma.wes.admin.resource.service

import com.soma.wes.admin.resource.dto.response.AdminOperationsOverviewResponse
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminOperationsOverviewService(
    private val resourceRepository: AdminResourceRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun get(): AdminOperationsOverviewResponse = AdminOperationsOverviewResponse(
        resourceCounts = resourceRepository.countAll(),
        operationalIssues = resourceRepository.findOperationalIssues(),
        recentFailedOperations = resourceRepository.findRecentFailedOperations(),
        trashPendingCount = resourceRepository.countTrashPending(),
        generatedAt = ZonedDateTime.now(clock),
    )
}
