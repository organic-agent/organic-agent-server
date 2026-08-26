package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.dto.AdminPhotoAccessMode
import com.soma.wes.admin.resource.dto.AdminPhotoAccessRequest
import com.soma.wes.admin.resource.dto.AdminPhotoAccessResponse
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.service.PhotoStorage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminPhotoAccessService(
    private val resourceRepository: AdminResourceRepository,
    private val photoStorage: PhotoStorage,
    private val storageProperties: StorageProperties,
    private val auditService: AdminAuditService,
    private val clock: Clock,
) {

    @Transactional
    fun access(
        actorAdminId: Long,
        photoId: Long,
        request: AdminPhotoAccessRequest,
        sourceAddress: String?,
    ): AdminPhotoAccessResponse {
        val photo = resourceRepository.findPhotoOriginal(photoId)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        val action = when (request.mode) {
            AdminPhotoAccessMode.VIEW -> AdminAuditAction.ORIGINAL_PHOTO_VIEWED
            AdminPhotoAccessMode.DOWNLOAD -> AdminAuditAction.ORIGINAL_PHOTO_DOWNLOADED
        }
        val url = when (request.mode) {
            AdminPhotoAccessMode.VIEW -> photoStorage.presignOriginal(photo.storageKey)
            AdminPhotoAccessMode.DOWNLOAD -> photoStorage.presignDownload(photo.storageKey, photo.originalFileName)
        }
        auditService.recordEvent(
            action = action,
            outcome = AdminAuditOutcome.SUCCESS,
            actorAdminId = actorAdminId,
            targetType = AdminAuditTargetType.PHOTO,
            targetId = photoId.toString(),
            targetLabel = photo.originalFileName,
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
        )
        return AdminPhotoAccessResponse(
            photoId = photoId,
            mode = request.mode,
            originalFileName = photo.originalFileName,
            url = url,
            expiresAt = ZonedDateTime.now(clock) + storageProperties.originalUrlTtl,
        )
    }
}
