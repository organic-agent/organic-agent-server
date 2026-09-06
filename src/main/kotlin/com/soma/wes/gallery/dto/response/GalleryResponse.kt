package com.soma.wes.gallery.dto.response

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.domain.GalleryWorkflowStatus
import com.soma.wes.gallery.domain.ShootType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "갤러리")
data class GalleryResponse(
    val id: Long,
    val workspaceId: Long,
    val createdByUserId: Long?,
    val title: String,
    val status: GalleryStatus,
    val workflowStatus: GalleryWorkflowStatus,
    @field:Schema(description = "갤러리 화면의 6단계 진행 상태")
    val stage: GalleryStage,
    val selectionDeadline: ZonedDateTime?,

    @field:Schema(description = "부부가 최종적으로 고를 사진 장수. null이면 제한이 없다")
    val maxSelectablePhotoCount: Int?,

    @field:Schema(description = "계약한 보정 요청 횟수. null이면 제한이 없다")
    val maxRetouchRoundCount: Int?,

    @field:Schema(description = "촬영 종류. AI 폴더의 큰 분류 목록이 이 값으로 갈린다.")
    val shootType: ShootType,

    val createdAt: ZonedDateTime?,
    val photoOrganizationRequired: Boolean = false,
    val foldersSavedAt: ZonedDateTime? = null,
    val retouchConfirmedAt: ZonedDateTime? = null,
    val archivedUntil: ZonedDateTime? = null,
    val planExpiresAt: ZonedDateTime? = null,
    val planMaxPhotoCount: Int? = null,
) {

    companion object {
        fun from(gallery: Gallery) = GalleryResponse(
            id = gallery.requiredId,
            workspaceId = gallery.workspaceId,
            createdByUserId = gallery.createdByUserId,
            title = gallery.title,
            status = gallery.status,
            workflowStatus = gallery.workflowStatus,
            stage = gallery.stage,
            selectionDeadline = gallery.selectionDeadline,
            maxSelectablePhotoCount = gallery.maxSelectablePhotoCount,
            maxRetouchRoundCount = gallery.maxRetouchRoundCount,
            shootType = gallery.shootType,
            createdAt = gallery.createdAt,
            photoOrganizationRequired = gallery.photoOrganizationRequired,
            foldersSavedAt = gallery.foldersSavedAt,
            retouchConfirmedAt = gallery.retouchConfirmedAt,
            archivedUntil = gallery.archivedUntil,
            planExpiresAt = gallery.planExpiresAt,
            planMaxPhotoCount = gallery.planMaxPhotoCount,
        )
    }
}
