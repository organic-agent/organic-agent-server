package com.soma.wes.admin.resource.dto


internal data class ExactPhotoRequestSeedDto(
    val galleryId: Long,
    val storageKey: String,
    val revisionId: Long,
)
