package com.soma.wes.admin.resource.dto

import com.soma.wes.admin.resource.domain.AdminInboxEventType
import com.soma.wes.admin.resource.domain.AdminResourceType
import jakarta.validation.constraints.PositiveOrZero
import java.time.ZonedDateTime

enum class AdminInboxWorkStatus { OPEN, CANCELED }

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

data class AdminNotificationReadRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
)
