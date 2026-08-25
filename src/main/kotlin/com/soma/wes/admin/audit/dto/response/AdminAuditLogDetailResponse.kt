package com.soma.wes.admin.audit.dto.response

data class AdminAuditLogDetailResponse(
    val audit: AdminAuditLogResponse,
    val revision: AdminEntityRevisionResponse?,
)
