package com.soma.wes.admin.resource.dto.response

import com.soma.wes.admin.resource.domain.AdminInboxEventType
import com.soma.wes.admin.resource.domain.AdminInboxWorkStatus
import com.soma.wes.admin.resource.domain.AdminResourceType
import java.time.ZonedDateTime

data class AdminNotificationInboxItemResponse(
    val id: Long,
    val version: Long,
    val eventType: AdminInboxEventType,
    val targetType: AdminResourceType,
    val targetId: Long,
    val workStatus: AdminInboxWorkStatus,
    val summary: String,
    val correlationId: String?,
    val read: Boolean,
    val readAt: ZonedDateTime?,
    val createdAt: ZonedDateTime,
)

data class AdminNotificationInboxPageResponse(
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val hasNext: Boolean,
    val unreadCount: Long,
    val contents: List<AdminNotificationInboxItemResponse>,
)
