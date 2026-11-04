package com.soma.wes.admin.resource.repository.projection

import com.soma.wes.admin.resource.domain.AdminRetouchArtifactType

internal data class CompletingRetouchArtifactUpload(
    val artifactType: AdminRetouchArtifactType,
    val storageKey: String,
    val contentType: String,
)
