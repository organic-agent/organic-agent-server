package com.soma.wes.gallery.dto.request

import com.soma.wes.gallery.domain.GalleryWorkflowStatus

data class ChangeWorkflowStatusRequest(
    val workflowStatus: GalleryWorkflowStatus,
)
