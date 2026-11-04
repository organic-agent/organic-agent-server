package com.soma.wes.admin.resource.dto

import com.soma.wes.admin.resource.domain.AdminRetouchArtifactType

internal sealed interface PreparedExternalWorkDto {
    data object None : PreparedExternalWorkDto
    data class Upload(
        val storageKey: String,
        val fileName: String,
        val contentType: String,
        val url: String,
        val expiresAt: java.time.Instant,
    ) : PreparedExternalWorkDto
    data class VerifiedReplacement(val replacementId: Long) : PreparedExternalWorkDto
    data class RetouchArtifactUpload(
        val retouchPhotoId: Long,
        val artifactType: AdminRetouchArtifactType,
        val storageKey: String,
        val fileName: String,
        val contentType: String,
        val url: String,
        val expiresAt: java.time.Instant,
    ) : PreparedExternalWorkDto
    data class VerifiedRetouchArtifact(
        val uploadId: Long,
        val retouchPhotoId: Long,
    ) : PreparedExternalWorkDto
}
