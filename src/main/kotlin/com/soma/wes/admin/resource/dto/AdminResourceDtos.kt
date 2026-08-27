package com.soma.wes.admin.resource.dto

import com.soma.wes.admin.audit.dto.response.AdminAuditLogResponse
import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminResourceType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.ZonedDateTime

data class AdminResourceSummaryResponse(
    val type: AdminResourceType,
    val id: Long,
    val version: Long,
    val label: String,
    val deleted: Boolean,
    val createdAt: ZonedDateTime?,
    val updatedAt: ZonedDateTime?,
)

data class AdminResourcePageResponse(
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val hasNext: Boolean,
    val contents: List<AdminResourceSummaryResponse>,
)

data class AdminResourceResponse(
    val type: AdminResourceType,
    val id: Long,
    val version: Long,
    val label: String,
    val deleted: Boolean,
    val fields: Map<String, Any?>,
    val createdAt: ZonedDateTime?,
    val updatedAt: ZonedDateTime?,
)

data class AdminResourceContextResponse(
    val resource: AdminResourceResponse,
    val relations: List<AdminResourceSummaryResponse>,
    val facts: Map<String, Any?>,
    val sections: Map<String, List<Map<String, Any?>>>,
    val sectionPageInfo: Map<String, AdminResourceSectionPageInfo> = emptyMap(),
)

data class AdminResourceSectionPageInfo(
    val totalCount: Long,
    val returnedCount: Int,
    val truncated: Boolean,
)

enum class AdminPhotoAccessMode { PREVIEW, VIEW, DOWNLOAD }

data class AdminPhotoAccessRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
    val mode: AdminPhotoAccessMode,
)

data class AdminPhotoAccessResponse(
    val photoId: Long,
    val mode: AdminPhotoAccessMode,
    val originalFileName: String,
    val url: String,
    val expiresAt: ZonedDateTime,
)

enum class AdminRetouchArtifactType { ANNOTATION, RESULT }

enum class AdminRetouchArtifactAccessMode { VIEW, DOWNLOAD }

data class AdminRetouchArtifactAccessRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
    val mode: AdminRetouchArtifactAccessMode,
)

data class AdminRetouchArtifactAccessResponse(
    val roundId: Long,
    val retouchPhotoId: Long,
    val artifactType: AdminRetouchArtifactType,
    val mode: AdminRetouchArtifactAccessMode,
    val originalFileName: String,
    val contentType: String,
    val url: String,
    val expiresAt: ZonedDateTime,
)

data class CreateAdminResourceRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
    val fields: Map<String, Any?>,
)

data class UpdateAdminResourceRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
    @field:PositiveOrZero
    val expectedVersion: Long,
    val fields: Map<String, Any?>,
)

data class ChangeAdminResourceStateRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
    @field:PositiveOrZero
    val expectedVersion: Long,
)

data class AdminReasonRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
)

data class AdminReprocessRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(max = 128)
    val idempotencyKey: String,
    val force: Boolean = false,
)

data class AdminReprocessResponse(
    val type: AdminResourceType,
    val id: Long,
    val idempotencyKey: String,
    val targets: Long,
    val accepted: Boolean,
)

data class AdminSystemSettingsResponse(
    val sessionAbsoluteTtlSeconds: Long,
    val sessionIdleTtlSeconds: Long,
    val maxFailedLoginAttempts: Int,
    val accountLockoutSeconds: Long,
    val revisionRetentionDays: Long,
    val trashRetentionDays: Long,
    val embeddingConfigured: Boolean,
    val uploadMaxBatchSize: Int,
    val uploadUrlTtlSeconds: Long,
    val viewUrlTtlSeconds: Long,
    val originalUrlTtlSeconds: Long,
    val mockGalleryConfigured: Boolean,
    val notificationConfigured: Boolean,
    val notificationInboxEnabled: Boolean,
    val featureFlags: Map<String, Boolean>,
    val grafanaConfigured: Boolean,
    val lokiConfigured: Boolean,
    val secretsMasked: Boolean = true,
    val mutable: Boolean = false,
)

data class AdminObservabilityLinksResponse(
    val correlationId: String,
    val grafanaUrl: String?,
    val lokiUrl: String?,
)

data class AdminResourceCountResponse(
    val active: Long,
    val deleted: Long,
    val total: Long,
)

data class AdminOperationalIssueResponse(
    val code: String,
    val label: String,
    val count: Long,
    val severity: String,
    val resourceType: AdminResourceType?,
)

data class AdminOperationRecordResponse(
    val id: Long,
    val action: String,
    val status: String,
    val targetType: AdminResourceType?,
    val targetId: String?,
    val failureCode: String?,
    val correlationId: String?,
    val attemptCount: Int,
    val createdAt: ZonedDateTime?,
    val updatedAt: ZonedDateTime?,
)

data class AdminOperationsOverviewResponse(
    val resourceCounts: Map<AdminResourceType, AdminResourceCountResponse>,
    val operationalIssues: List<AdminOperationalIssueResponse>,
    val recentFailedOperations: List<AdminOperationRecordResponse>,
    val trashPendingCount: Long,
    val recentAudits: List<AdminAuditLogResponse>,
    val generatedAt: ZonedDateTime,
)

data class AdminTrashBatchResponse(
    val id: Long,
    val rootType: AdminResourceType,
    val rootId: Long,
    val rootLabel: String,
    val status: String,
    val actorUsername: String?,
    val reason: String,
    val deletedAt: ZonedDateTime,
    val restoreUntil: ZonedDateTime,
    val purgeEligibleAt: ZonedDateTime,
    val restoreWindowDays: Long,
    val restorable: Boolean,
    val restoredAt: ZonedDateTime?,
    val purgedAt: ZonedDateTime?,
    val purgeAttemptCount: Int,
    val failureCode: String?,
    val affectedCounts: Map<String, Long>,
    val relationshipFacts: Map<String, Long>,
    val entries: List<AdminTrashEntryResponse>,
)

data class AdminTrashEntryResponse(
    val resourceType: String,
    val resourceId: Long,
    val root: Boolean,
    val relationPath: String,
)

data class AdminChildTrashResponse(
    val id: Long,
    val resourceType: AdminChildTrashType,
    val resourceId: Long,
    val parentType: AdminResourceType,
    val parentId: Long,
    val status: String,
    val actorUsername: String?,
    val reason: String,
    val deletedAt: ZonedDateTime,
    val restoreUntil: ZonedDateTime,
    val purgeEligibleAt: ZonedDateTime,
    val restoreWindowDays: Long,
    val restorable: Boolean,
    val restoredAt: ZonedDateTime?,
    val purgedAt: ZonedDateTime?,
    val purgeAttemptCount: Int,
    val failureCode: String?,
)
