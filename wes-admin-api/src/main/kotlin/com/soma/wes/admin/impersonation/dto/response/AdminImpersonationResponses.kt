package com.soma.wes.admin.impersonation.dto.response

import com.soma.wes.admin.resource.domain.AdminResourceType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime
import java.util.UUID

data class AdminImpersonationAdminResponse(
    val id: Long,
    val username: String,
    val displayName: String,
)

data class AdminImpersonationResponse(
    val id: UUID,
    @field:Schema(
        description = "이 응답을 만든 현재 HTTP 요청의 16자리 trace. 대리보기 세션 식별자는 id다. 요청 trace가 16자리 소문자 hex가 아니면 null이다.",
        example = "3f9a1c2b4d5e6f70",
    )
    val correlationId: String?,
    val admin: AdminImpersonationAdminResponse,
    val targetType: AdminResourceType,
    val targetId: Long,
    val viewer: AdminImpersonationViewerResponse,
    val view: AdminImpersonationViewResponse,
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

data class AdminImpersonationViewerResponse(
    val userId: Long,
    /** 사용자 고정 권한이 아니라 현재 대리보기 대상에 대한 접근 근거다. */
    val accessRole: String,
)

data class AdminImpersonationViewResponse(
    val profile: Map<String, Any?>,
    val workspaces: List<AdminImpersonationWorkspaceViewResponse>,
    val galleries: List<AdminImpersonationGalleryViewResponse>,
    /** 실제 사용자 API의 권한 검사를 통과해 구성한, BackOffice generic renderer용 read model. */
    val sections: Map<String, List<Map<String, Any?>>> = emptyMap(),
    val sectionCounts: Map<String, Int> = sections.mapValues { (_, rows) -> rows.size },
    val sectionFields: Map<String, List<String>> = sections.mapValues { (_, rows) ->
        rows.flatMap { it.keys }.distinct()
    },
    val capabilities: List<String> = listOf(
        "READ_PROFILE",
        "READ_GALLERIES",
        "READ_PHOTOS",
        "READ_SELECTION_STATUS",
        "READ_COLLABORATION_SUMMARY",
        "READ_CATEGORIES",
        "READ_RETOUCH_STATUS",
    ),
)

data class AdminImpersonationWorkspaceViewResponse(
    val workspaceId: Long,
    val workspaceType: String,
    val name: String,
    val accessRole: String,
    @field:Schema(
        description = "스튜디오 공개 주소 식별자. 개인 작업공간은 스튜디오가 없어 null이다.",
        example = "organic-studio",
    )
    val galleryUrl: String?,
    @field:Schema(description = "스튜디오 연락처. 개인 작업공간은 null", maxLength = 100)
    val contact: String?,
    @field:Schema(description = "스튜디오 소개. 개인 작업공간은 null", maxLength = 500)
    val description: String?,
)

data class AdminImpersonationGalleryViewResponse(
    val id: Long,
    val workspaceId: Long,
    val workspaceType: String,
    @field:Schema(
        description = "갤러리를 만든 사용자 id. 그 사용자가 삭제되면 null이다(FK ON DELETE SET NULL).",
        example = "7",
    )
    val createdByUserId: Long?,
    val title: String,
    val publicStatus: String,
    val workflowStatus: String,
    @field:Schema(
        description = "status 및 workflowStatus와 분리된 읽기 전용 6단계 제품 진행 상태",
        allowableValues = [
            "UPLOAD",
            "SELECTION_IN_PROGRESS",
            "SELECTION_COMPLETED",
            "RETOUCH",
            "DELIVERY",
            "ARCHIVED",
        ],
    )
    val stage: String,
    @field:Schema(
        description = "사진 선택 마감 기한. 기한 없이 열어 둔 갤러리면 null이다.",
        example = "2026-09-30T23:59:59+09:00",
    )
    val selectionDeadline: ZonedDateTime?,
    val accessRole: String,
    val photoCount: Long,
    @field:Schema(
        description = "갤러리 셀렉 상태(SELECTING·SUBMITTED). 살아 있는 셀렉 행이 없으면 null이다.",
        example = "SUBMITTED",
    )
    val selectionStatus: String?,
    val collaborationCount: Long,
    val commentCount: Long,
)
