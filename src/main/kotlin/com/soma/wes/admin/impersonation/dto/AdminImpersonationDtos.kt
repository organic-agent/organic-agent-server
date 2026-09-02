package com.soma.wes.admin.impersonation.dto

import com.soma.wes.admin.resource.domain.AdminResourceType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.ZonedDateTime
import java.util.UUID

data class StartAdminImpersonationRequest(
    val targetType: AdminResourceType,
    @field:Positive
    val targetId: Long,
    @field:Positive
    val viewerUserId: Long? = null,
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
    /** 이 응답을 만든 현재 HTTP 요청의 16자리 trace. 대리보기 세션 식별자는 [id]다. */
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
        "READ_ALBUMS",
        "READ_RETOUCH_STATUS",
    ),
)

data class AdminImpersonationWorkspaceViewResponse(
    val workspaceId: Long,
    val workspaceType: String,
    val name: String,
    val accessRole: String,
    val galleryUrl: String?,
)

data class AdminImpersonationGalleryViewResponse(
    val id: Long,
    val workspaceId: Long,
    val workspaceType: String,
    val createdByUserId: Long?,
    val title: String,
    val publicStatus: String,
    val workflowStatus: String,
    val selectionDeadline: ZonedDateTime?,
    val accessRole: String,
    val photoCount: Long,
    val selectionStatus: String?,
    val collaborationCount: Long,
    val commentCount: Long,
)
