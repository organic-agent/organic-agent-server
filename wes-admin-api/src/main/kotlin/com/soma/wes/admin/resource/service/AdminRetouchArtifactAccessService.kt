package com.soma.wes.admin.resource.service

import com.soma.wes.admin.resource.dto.AdminRetouchArtifactAccessMode
import com.soma.wes.admin.resource.dto.AdminRetouchArtifactAccessRequest
import com.soma.wes.admin.resource.dto.AdminRetouchArtifactAccessResponse
import com.soma.wes.admin.resource.dto.AdminRetouchArtifactType
import com.soma.wes.admin.resource.repository.AdminRetouchArtifactRepository
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.service.port.PhotoStorage
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminRetouchArtifactAccessService(
    private val repository: AdminRetouchArtifactRepository,
    private val photoStorage: PhotoStorage,
    private val storageProperties: StorageProperties,
    private val clock: Clock,
) {

    /** storage key는 이 메서드 밖으로 나가지 않고 서명 URL만 응답한다. */
    @Transactional
    fun access(
        actorAdminId: Long,
        roundId: Long,
        retouchPhotoId: Long,
        artifactType: AdminRetouchArtifactType,
        request: AdminRetouchArtifactAccessRequest,
        sourceAddress: String?,
    ): AdminRetouchArtifactAccessResponse {
        val artifact = repository.access(roundId, retouchPhotoId, artifactType)
        val url = when (request.mode) {
            AdminRetouchArtifactAccessMode.VIEW -> photoStorage.presignView(artifact.storageKey)
            AdminRetouchArtifactAccessMode.DOWNLOAD ->
                photoStorage.presignDownload(artifact.storageKey, artifact.originalFileName)
        }
        val ttl = when (request.mode) {
            AdminRetouchArtifactAccessMode.VIEW -> storageProperties.viewUrlTtl
            AdminRetouchArtifactAccessMode.DOWNLOAD -> storageProperties.originalUrlTtl
        }
        return AdminRetouchArtifactAccessResponse(
            roundId = roundId,
            retouchPhotoId = retouchPhotoId,
            artifactType = artifactType,
            mode = request.mode,
            originalFileName = artifact.originalFileName,
            contentType = artifact.contentType,
            url = url,
            expiresAt = ZonedDateTime.now(clock) + ttl,
        )
    }
}
