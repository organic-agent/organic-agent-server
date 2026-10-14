package com.soma.wes.studio.dto.response

import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryInviteStatus
import java.time.ZonedDateTime

data class StudioInviteResponse(
    val id: Long,
    val workspaceId: Long,
    val kind: GalleryInviteKind = GalleryInviteKind.STUDIO_MEMBER,
    val inviteUrl: String,
    val status: GalleryInviteStatus,
    val usedCount: Int,
    val expiresAt: ZonedDateTime,
    val revokedAt: ZonedDateTime?,
)
