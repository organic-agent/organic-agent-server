package com.soma.wes.admin.resource.dto.response

import com.soma.wes.admin.resource.domain.AdminResourceType
import io.swagger.v3.oas.annotations.media.Schema
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
    val sectionPageInfo: Map<String, SectionPageInfo> = emptyMap(),
) {
    data class SectionPageInfo(
        val totalCount: Long,
        val returnedCount: Int,
        val truncated: Boolean,
    )
}

data class AdminResourceCountResponse(
    val active: Long,
    val deleted: Long,
    val total: Long,
)
