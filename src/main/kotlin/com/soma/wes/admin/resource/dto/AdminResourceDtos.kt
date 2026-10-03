package com.soma.wes.admin.resource.dto

import com.soma.wes.admin.audit.dto.response.AdminAuditLogResponse
import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminReprocessScope
import com.soma.wes.admin.resource.domain.AdminResourceType
import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(
        description = "리소스별 필드. STUDIO는 contact/description과 읽기 전용 inflowChannel, GALLERY는 status/workflowStatus와 분리된 읽기 전용 stage(UPLOAD/SELECTION_IN_PROGRESS/SELECTION_COMPLETED/RETOUCH/DELIVERY/ARCHIVED)를 포함한다.",
    )
    val fields: Map<String, Any?>,
    val createdAt: ZonedDateTime?,
    val updatedAt: ZonedDateTime?,
)

data class AdminResourceContextResponse(
    val resource: AdminResourceResponse,
    val relations: List<AdminResourceSummaryResponse>,
    @field:Schema(description = "파생 운영 사실. GALLERY는 읽기 전용 stage와 최신 inviteStatus를 포함한다.")
    val facts: Map<String, Any?>,
    @field:Schema(
        description = "관련 조회 섹션. USER/STUDIO/GALLERY의 userNotifications와 userNotificationSettings는 읽기 전용이며 관리자 inbox와 별도다.",
    )
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
    /** 생략하면 [AdminReprocessScope.ALL] — 이 필드가 생기기 전의 요청과 같은 동작이다. */
    val scope: AdminReprocessScope = AdminReprocessScope.ALL,
)

data class AdminReprocessResponse(
    val type: AdminResourceType,
    val id: Long,
    val idempotencyKey: String,
    val scope: AdminReprocessScope,
    val targets: Long,
    val accepted: Boolean,
)

/** 갤러리에서 분석이 결정적으로 실패한 사진 목록. [AdminReprocessScope.FAILED_ONLY] 재처리가 되돌릴 대상과 같다. */
data class AdminAnalysisFailuresResponse(
    val galleryId: Long,
    val failed: Int,
    val photos: List<Photo>,
) {
    data class Photo(
        val photoId: Long,
        val originalFileName: String,
        /** `photo_analysis.error` 값 그대로 — `EMBED_ATTEMPTS_EXCEEDED`·`ANALYSIS_STALLED` 또는 AI 실행기가 쓴 사유. */
        val error: String,
        val failedAt: ZonedDateTime,
    )
}

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
