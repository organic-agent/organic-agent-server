package com.soma.wes.studio.dto.response

import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryInviteStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class StudioInviteResponse(
    val id: Long,
    val workspaceId: Long,
    val kind: GalleryInviteKind = GalleryInviteKind.STUDIO_MEMBER,
    val inviteUrl: String,
    val status: GalleryInviteStatus,
    val usedCount: Int,
    val expiresAt: ZonedDateTime,
    @field:Schema(
        description = "링크가 폐기된 시각. 새 링크를 발급하면 이전 링크가 폐기되며, 발급·현재 링크 조회는 살아 있는 링크만 돌려주므로 응답에서는 null이다.",
        example = "2026-11-02T09:30:00+09:00",
    )
    val revokedAt: ZonedDateTime?,
)
