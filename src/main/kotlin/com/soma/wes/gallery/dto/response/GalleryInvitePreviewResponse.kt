package com.soma.wes.gallery.dto.response

import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryInviteStatus
import java.time.ZonedDateTime

data class GalleryInvitePreviewResponse(
    val kind: GalleryInviteKind,
    val status: GalleryInviteStatus,
    val workspaceId: Long,
    val studioName: String?,
    val galleryId: Long?,
    val galleryTitle: String?,
    val maxUses: Int?,
    val usedCount: Int,
    val remainingUses: Int?,
    val expiresAt: ZonedDateTime,
    /** 수락 전 표시할 발급자 이름. 탈퇴했거나 확인 불가하면 null이다. */
    val inviterNickname: String? = null,
)
