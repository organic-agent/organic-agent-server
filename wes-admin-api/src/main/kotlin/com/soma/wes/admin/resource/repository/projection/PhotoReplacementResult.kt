package com.soma.wes.admin.resource.repository.projection


data class PhotoReplacementResult(
    val revisionId: Long,
    val revisionNumber: Long,
    val newStorageKey: String,
    val galleryId: Long,
    val jobIds: List<Long>,
)
