package com.soma.wes.gallery.dto.response

import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryInviteStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class GalleryInvitePreviewResponse(
    val kind: GalleryInviteKind,
    val status: GalleryInviteStatus,
    val workspaceId: Long,
    @field:Schema(description = "초대한 스튜디오 이름. 개인 갤러리 초대처럼 작업공간이 스튜디오가 아니면 null이다.", example = "오가닉 스튜디오")
    val studioName: String?,
    @field:Schema(description = "초대받은 갤러리 id. 스튜디오 초대 링크면 갤러리가 없어 null이다.", example = "1")
    val galleryId: Long?,
    @field:Schema(description = "초대받은 갤러리 이름. 스튜디오 초대 링크면 null이다.", example = "우리의 웨딩 리허설")
    val galleryTitle: String?,
    @field:Schema(description = "링크로 들어올 수 있는 최대 인원. 스튜디오 초대 링크는 인원 제한이 없어 null이다.", example = "2")
    val maxUses: Int?,
    val usedCount: Int,
    @field:Schema(description = "남은 사용 횟수(최대 인원 - 사용 횟수, 0 아래로 내려가지 않는다). 스튜디오 초대 링크는 null이다.", example = "1")
    val remainingUses: Int?,
    val expiresAt: ZonedDateTime,
    @field:Schema(description = "수락 전 표시할 발급자 닉네임. 발급자가 탈퇴했거나 확인할 수 없으면 null이다.", example = "민지")
    val inviterNickname: String? = null,
)
