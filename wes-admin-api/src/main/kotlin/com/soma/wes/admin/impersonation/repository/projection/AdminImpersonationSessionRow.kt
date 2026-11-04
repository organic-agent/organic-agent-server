package com.soma.wes.admin.impersonation.repository.projection

import com.soma.wes.admin.resource.domain.AdminResourceType
import java.time.ZonedDateTime
import java.util.UUID

data class AdminImpersonationSessionRow(
    val id: UUID,
    val adminId: Long,
    val adminSessionId: UUID,
    val viewerUserId: Long,
    val viewerAccessRole: String,
    val targetType: AdminResourceType,
    val targetId: Long,
    val targetLabel: String,

    val sourceAddress: String?,
    /** 대리보기 시작 요청의 MDC trace. 생명주기 식별자는 [id]다. */
    val correlationId: String?,
    val startedAt: ZonedDateTime,
    val expiresAt: ZonedDateTime,
)
