package com.soma.wes.admin.resource.repository.projection


data class RetouchArtifactAccess(
    val storageKey: String,
    val originalFileName: String,
    val contentType: String,
)
