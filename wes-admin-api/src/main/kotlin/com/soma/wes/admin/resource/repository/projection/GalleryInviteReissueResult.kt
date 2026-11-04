package com.soma.wes.admin.resource.repository.projection

import com.soma.wes.gallery.domain.GalleryInviteKind

data class GalleryInviteReissueResult(
    val inviteId: Long,
    val kind: GalleryInviteKind,
    val maxUses: Int,
)
