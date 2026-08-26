package com.soma.wes.admin.impersonation.dto

import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminResourceContextResponse
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.ZonedDateTime
import java.util.UUID

data class StartAdminImpersonationRequest(
    val targetType: AdminResourceType,
    @field:Positive
    val targetId: Long,
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
)

data class AdminImpersonationAdminResponse(
    val id: Long,
    val username: String,
    val displayName: String,
)

data class AdminImpersonationResponse(
    val id: UUID,
    val admin: AdminImpersonationAdminResponse,
    val context: AdminResourceContextResponse,
    val readOnly: Boolean = true,
    val blockedCapabilities: List<String> = listOf(
        "SAVE",
        "SUBMIT",
        "COMMENT",
        "DOWNLOAD",
        "MUTATION_API",
    ),
    val startedAt: ZonedDateTime,
    val expiresAt: ZonedDateTime,
)
