package com.soma.wes.gallery.dto.response

import com.soma.wes.album.domain.AlbumStatus
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
    @field:Schema(description = "갤러리를 만든 사용자 id. 그 계정이 삭제되면 null이 된다.", example = "42")
    val createdByUserId: Long?,
    val title: String,
    val status: GalleryStatus,
    val workflowStatus: GalleryWorkflowStatus,
    @field:Schema(description = "갤러리 화면의 7단계 진행 상태")
    val stage: GalleryStage,
    @field:Schema(
        description = "셀렉 마감일. null이면 마감이 없다. 스튜디오 갤러리는 지나면 고르기가 막히고, 개인 갤러리는 D-day를 세는 목표일일 뿐 고르기를 막지 않는다.",
        example = "2026-12-31T23:59:59+09:00",
    )
    val selectionDeadline: ZonedDateTime?,

    @field:Schema(description = "부부가 최종적으로 고를 사진 장수. null이면 제한이 없다")
    val maxSelectablePhotoCount: Int?,

    @field:Schema(description = "계약한 보정 요청 횟수. null이면 제한이 없다")
    val maxRetouchRoundCount: Int?,

    @field:Schema(description = "촬영 종류. AI 폴더의 컨셉 목록이 이 값으로 갈린다.")
    val shootType: ShootType,

    @field:Schema(description = "갤러리를 만든 시각. 저장할 때 서버가 채운다.", example = "2026-11-02T09:30:00+09:00")
    val createdAt: ZonedDateTime?,
    val photoOrganizationRequired: Boolean = false,
    @field:Schema(description = "고객이 폴더 정리를 확정한 시각. 확정은 한 번만 되며, 확정 전에는 null이다.", example = "2026-11-10T21:00:00+09:00")
    val foldersSavedAt: ZonedDateTime? = null,
    @field:Schema(description = "고객이 보정본을 최종 확정한 시각. 앨범 초안이 있으면 앨범 구성 단계로 넘어간다. 확정 전에는 null이다.", example = "2026-12-01T18:00:00+09:00")
    val retouchConfirmedAt: ZonedDateTime? = null,
    @field:Schema(
        description = "보관된 갤러리를 남겨 두는 기한. 닫기·보정 확정·이용 기간 만료로 보관될 때 보관 일수 설정이 있으면 채워지고, 그 뒤 정리 작업이 지운다. 보관 전이거나 설정이 없으면 null이다.",
        example = "2027-01-01T18:00:00+09:00",
    )
    val archivedUntil: ZonedDateTime? = null,
    @field:Schema(
        description = "개인 이용권의 만료 시각. 지나면 수정이 막히고 갤러리가 보관 상태로 바뀐다. 이용권 없이 만든 갤러리는 null이다.",
        example = "2026-12-02T09:30:00+09:00",
    )
    val planExpiresAt: ZonedDateTime? = null,
    @field:Schema(description = "이용권이 허용하는 최대 사진 수. 이용권 없이 만든 갤러리는 null이다.", example = "500")
    val planMaxPhotoCount: Int? = null,
    @field:Schema(description = "free 또는 pro. 요금제가 없거나 이전 테스트 이용권으로 만든 갤러리는 null")
    val planId: String? = null,
    @field:Schema(description = "현재 사용자에게 보이는 앨범 여부. 발송 전 초안은 스튜디오만 본다.")
    val hasAlbum: Boolean = false,
    val albumStatus: AlbumStatus = AlbumStatus.NONE,
) {

    companion object {
        fun from(gallery: Gallery, showAlbumDraft: Boolean = true): GalleryResponse {
            val visibleAlbumStatus = if (!showAlbumDraft && gallery.albumStatus == AlbumStatus.DRAFT) {
                AlbumStatus.NONE
            } else {
                gallery.albumStatus
            }
            return GalleryResponse(
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
                planId = gallery.planType?.planId,
                hasAlbum = visibleAlbumStatus != AlbumStatus.NONE,
                albumStatus = visibleAlbumStatus,
            )
        }
    }
}
