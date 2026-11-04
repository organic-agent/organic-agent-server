package com.soma.wes.admin.resource.repository.projection


internal data class WorkflowPhotoCurrent(
    val galleryId: Long,
    val storageKey: String,
    val previewKey: String?,
    val originalFileName: String,
    val contentType: String,
)
