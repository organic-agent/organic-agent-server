package com.soma.wes.admin.resource.dto.response

import com.soma.wes.admin.resource.domain.AdminResourceType
import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(
        description = "correlationId로 로그를 거른 Grafana Explore 링크. app.admin.observability.grafana-base-url이 http(s) 주소로 설정되지 않았으면 null이다.",
    )
    val grafanaUrl: String?,
    @field:Schema(
        description = "같은 trace를 찾는 Loki query_range 링크(Grafana datasource proxy 경유). app.admin.observability.loki-base-url이 http(s) 주소로 설정되지 않았으면 null이다.",
    )
    val lokiUrl: String?,
)

data class AdminOperationalIssueResponse(
    val code: String,
    val label: String,
    val count: Long,
    val severity: String,
    @field:Schema(
        description = "이 이슈가 가리키는 리소스 종류. 특정 리소스 종류에 묶이지 않는 이슈면 null이다.",
        example = "PHOTO",
    )
    val resourceType: AdminResourceType?,
)

data class AdminOperationRecordResponse(
    val id: Long,
    val action: String,
    val status: String,
    @field:Schema(
        description = "실패한 관리자 요청의 대상 종류. 기록이 없거나 지금의 AdminResourceType에 없는 값이면 null이다.",
        example = "GALLERY",
    )
    val targetType: AdminResourceType?,
    @field:Schema(
        description = "실패한 관리자 요청의 대상 id(문자열로 저장). 기록이 없으면 null이다.",
        example = "42",
    )
    val targetId: String?,
    @field:Schema(
        description = "실패 사유. 예외 클래스 이름이나 RESOURCE_VERSION_CONFLICT 같은 코드를 80자까지 남기며 기록이 없으면 null이다.",
        example = "RESOURCE_VERSION_CONFLICT",
    )
    val failureCode: String?,
    @field:Schema(
        description = "요청을 예약할 때의 trace. trace가 없던 요청은 untracked로 남으며 기록이 없으면 null이다.",
        example = "3f9a1c2b4d5e6f70",
    )
    val correlationId: String?,
    val attemptCount: Int,
    @field:Schema(
        description = "멱등성 키를 예약한 시각. 기록이 없으면 null이다.",
        example = "2026-11-04T10:00:00+09:00",
    )
    val createdAt: ZonedDateTime?,
    @field:Schema(
        description = "마지막 상태 변경(실패 기록) 시각. 최근 실패 목록은 이 값의 최신순이며 기록이 없으면 null이다.",
        example = "2026-11-04T10:00:05+09:00",
    )
    val updatedAt: ZonedDateTime?,
)

data class AdminOperationsOverviewResponse(
    val resourceCounts: Map<AdminResourceType, AdminResourceCountResponse>,
    val operationalIssues: List<AdminOperationalIssueResponse>,
    val recentFailedOperations: List<AdminOperationRecordResponse>,
    val trashPendingCount: Long,
    val generatedAt: ZonedDateTime,
)
