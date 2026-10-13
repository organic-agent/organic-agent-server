package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.service.AdminAuditService
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
    private val auditService: AdminAuditService,
    private val clock: Clock,
) {

    /** storage key는 이 메서드 밖으로 나가지 않고, 서명 URL도 영구 감사에는 기록하지 않는다. */
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
        val action = when (request.mode) {
            AdminRetouchArtifactAccessMode.VIEW -> AdminAuditAction.RETOUCH_ARTIFACT_VIEWED
            AdminRetouchArtifactAccessMode.DOWNLOAD -> AdminAuditAction.RETOUCH_ARTIFACT_DOWNLOADED
        }
        val url = when (request.mode) {
            AdminRetouchArtifactAccessMode.VIEW -> photoStorage.presignView(artifact.storageKey)
            AdminRetouchArtifactAccessMode.DOWNLOAD ->
                photoStorage.presignDownload(artifact.storageKey, artifact.originalFileName)
        }
        auditService.recordEvent(
            action = action,
            outcome = AdminAuditOutcome.SUCCESS,
            actorAdminId = actorAdminId,
            targetType = AdminAuditTargetType.RETOUCH_REQUEST,
            targetId = "RA-$roundId-$retouchPhotoId-${artifactType.name}",
            targetLabel = "${artifactType.name.lowercase()}:${artifact.originalFileName}",
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
        )
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
