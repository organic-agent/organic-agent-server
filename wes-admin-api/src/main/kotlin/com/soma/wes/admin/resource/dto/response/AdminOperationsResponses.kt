package com.soma.wes.admin.resource.dto.response

import com.soma.wes.admin.resource.domain.AdminResourceType
import java.time.ZonedDateTime

data class AdminSystemSettingsResponse(
    val sessionAbsoluteTtlSeconds: Long,
    val sessionIdleTtlSeconds: Long,
    val maxFailedLoginAttempts: Int,
    val accountLockoutSeconds: Long,
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
    val mutable: Boolean = false,
)

data class AdminObservabilityLinksResponse(
    val correlationId: String,
    val grafanaUrl: String?,
    val lokiUrl: String?,
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
    val generatedAt: ZonedDateTime,
)
