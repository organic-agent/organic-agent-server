package com.soma.wes.admin.resource.dto.response

import com.soma.wes.admin.resource.domain.AdminPhotoAccessMode
import com.soma.wes.admin.resource.domain.AdminRetouchArtifactAccessMode
import com.soma.wes.admin.resource.domain.AdminRetouchArtifactType
import java.time.ZonedDateTime

data class AdminPhotoAccessResponse(
    val photoId: Long,
    val mode: AdminPhotoAccessMode,
    val originalFileName: String,
    val url: String,
    val expiresAt: ZonedDateTime,
)

data class AdminRetouchArtifactAccessResponse(
    val roundId: Long,
    val retouchPhotoId: Long,
    val artifactType: AdminRetouchArtifactType,
    val mode: AdminRetouchArtifactAccessMode,
    val originalFileName: String,
    val contentType: String,
    val url: String,
    val expiresAt: ZonedDateTime,
)
