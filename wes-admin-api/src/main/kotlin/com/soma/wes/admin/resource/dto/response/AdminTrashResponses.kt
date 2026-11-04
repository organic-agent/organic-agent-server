package com.soma.wes.admin.resource.dto.response

import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminResourceType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class AdminTrashBatchResponse(
    val id: Long,
    val rootType: AdminResourceType,
    val rootId: Long,
    val rootLabel: String,
    val status: String,
    @field:Schema(
        description = "휴지통으로 보낸 관리자의 username(삭제 시점 값). 영구 삭제(PURGED)되면 지워져 null이다.",
        example = "ops-admin",
    )
    val actorUsername: String?,
    val deletedAt: ZonedDateTime,
    val restoreUntil: ZonedDateTime,
    val purgeEligibleAt: ZonedDateTime,
    val restoreWindowDays: Long,
    val restorable: Boolean,
    @field:Schema(
        description = "복원한 시각. 복원되지 않았으면 null이다.",
        example = "2026-11-05T09:00:00+09:00",
    )
    val restoredAt: ZonedDateTime?,
    @field:Schema(
        description = "영구 삭제를 마친 시각. 아직 영구 삭제되지 않았으면 null이다.",
        example = "2026-12-04T03:00:00+09:00",
    )
    val purgedAt: ZonedDateTime?,
    val purgeAttemptCount: Int,
    @field:Schema(
        description = "마지막 영구 삭제 실패의 예외 이름(120자까지). 재시도 상한에 닿으면 status가 PURGE_FAILED가 되고, 복원·영구 삭제 성공 때는 null로 지운다.",
        example = "S3Exception",
    )
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
    @field:Schema(
        description = "휴지통으로 보낸 관리자의 username(삭제 시점 값). 영구 삭제(PURGED)되면 지워져 null이다.",
        example = "ops-admin",
    )
    val actorUsername: String?,
    val deletedAt: ZonedDateTime,
    val restoreUntil: ZonedDateTime,
    val purgeEligibleAt: ZonedDateTime,
    val restoreWindowDays: Long,
    val restorable: Boolean,
    @field:Schema(
        description = "복원한 시각. 복원되지 않았으면 null이다.",
        example = "2026-11-05T09:00:00+09:00",
    )
    val restoredAt: ZonedDateTime?,
    @field:Schema(
        description = "영구 삭제를 마친 시각. 아직 영구 삭제되지 않았으면 null이다.",
        example = "2026-12-04T03:00:00+09:00",
    )
    val purgedAt: ZonedDateTime?,
    val purgeAttemptCount: Int,
    @field:Schema(
        description = "마지막 영구 삭제 실패의 예외 이름(120자까지). 재시도 상한에 닿으면 status가 PURGE_FAILED가 되고, 복원·영구 삭제 성공 때는 null로 지운다.",
        example = "S3Exception",
    )
    val failureCode: String?,
)
