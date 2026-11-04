package com.soma.wes.admin.impersonation.dto.request

import com.soma.wes.admin.resource.domain.AdminResourceType
import jakarta.validation.constraints.Positive

data class StartAdminImpersonationRequest(
    val targetType: AdminResourceType,
    @field:Positive
    val targetId: Long,
    @field:Positive
    val viewerUserId: Long? = null,
)
