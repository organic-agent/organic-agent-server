package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.dto.AdminNotificationInboxItemResponse
import com.soma.wes.admin.resource.dto.AdminNotificationInboxPageResponse
import com.soma.wes.admin.resource.repository.AdminNotificationInboxRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminNotificationInboxService(
    private val repository: AdminNotificationInboxRepository,
    private val auditService: AdminAuditService,
) {

    @Transactional(readOnly = true)
    fun list(adminId: Long, page: Int, size: Int): AdminNotificationInboxPageResponse {
        if (page < 0 || size !in 1..100) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        return repository.findPage(adminId, page, size)
    }

    @Transactional
    fun markRead(
        adminId: Long,
        notificationId: Long,
        expectedVersion: Long,
        sourceAddress: String?,
    ): AdminNotificationInboxItemResponse {
        val before = repository.findOne(adminId, notificationId)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        if (before.version != expectedVersion) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)

        val inserted = repository.markRead(adminId, notificationId, expectedVersion)
        val result = repository.findOne(adminId, notificationId)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        if (!result.read || result.version != expectedVersion) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        if (inserted) {
            auditService.recordEvent(
                action = AdminAuditAction.RESOURCE_UPDATED,
                outcome = AdminAuditOutcome.SUCCESS,
                actorAdminId = adminId,
                targetType = AdminAuditTargetType.ADMIN_OPERATION,
                targetId = notificationId.toString(),
                targetLabel = null,
                reason = null,
                sourceAddress = sourceAddress,
            )
        }
        return result
    }
}
