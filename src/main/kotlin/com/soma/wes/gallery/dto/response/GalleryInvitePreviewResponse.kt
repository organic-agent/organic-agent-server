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
)
