package com.soma.wes.admin.resource.repository.projection


data class RetouchArtifactUploadCreated(
    val uploadId: Long,
    val roundVersion: Long,
    val retouchPhotoVersion: Long,
)
