package com.soma.wes.admin.resource.repository.projection

import com.soma.wes.gallery.domain.GalleryInviteKind

internal data class WorkflowGalleryInvitePolicy(
    val galleryVersion: Long,
    val workspaceType: String,
    val previousKind: GalleryInviteKind?,
    val previousMaxUses: Int?,
)
