package com.soma.wes.admin.resource.repository.projection


data class AdminPhotoOriginal(
    val id: Long,
    val originalFileName: String,
    val storageKey: String,
    val previewKey: String? = null,
)
