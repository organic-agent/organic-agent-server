package com.soma.wes.admin.resource.dto.response

import com.soma.wes.admin.resource.domain.AdminInboxEventType
import com.soma.wes.admin.resource.domain.AdminInboxWorkStatus
import com.soma.wes.admin.resource.domain.AdminResourceType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class AdminNotificationInboxItemResponse(
    val id: Long,
    val version: Long,
    val eventType: AdminInboxEventType,
    val targetType: AdminResourceType,
    val targetId: Long,
    val workStatus: AdminInboxWorkStatus,
    val summary: String,
    @field:Schema(
        description = "알림을 만든 관리자 요청(RESEND_NOTIFICATION)의 16자리 trace. 그 요청 trace가 16자리 소문자 hex가 아니었으면 null이다.",
        example = "3f9a1c2b4d5e6f70",
    )
    val correlationId: String?,
    val read: Boolean,
    @field:Schema(
        description = "지금 로그인한 관리자가 읽음 처리한 시각. 읽음은 관리자별이며 아직 읽지 않았으면 null이고 read도 false다.",
        example = "2026-11-04T10:00:00+09:00",
    )
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
