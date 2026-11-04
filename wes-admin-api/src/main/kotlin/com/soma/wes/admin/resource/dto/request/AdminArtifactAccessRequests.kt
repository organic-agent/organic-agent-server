package com.soma.wes.admin.resource.dto.request

import com.soma.wes.admin.resource.domain.AdminPhotoAccessMode
import com.soma.wes.admin.resource.domain.AdminRetouchArtifactAccessMode

data class AdminPhotoAccessRequest(
    val mode: AdminPhotoAccessMode,
)

data class AdminRetouchArtifactAccessRequest(
    val mode: AdminRetouchArtifactAccessMode,
)
