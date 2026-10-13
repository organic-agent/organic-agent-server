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
import com.soma.wes.photo.service.port.PhotoStorage
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
            AdminPhotoAccessMode.PREVIEW -> AdminAuditAction.PHOTO_PREVIEW_VIEWED
            AdminPhotoAccessMode.VIEW -> AdminAuditAction.ORIGINAL_PHOTO_VIEWED
            AdminPhotoAccessMode.DOWNLOAD -> AdminAuditAction.ORIGINAL_PHOTO_DOWNLOADED
        }
        val url = when (request.mode) {
            // 목업 목록은 파생 미리보기만 허용한다. 없을 때 원본으로 폴백하면 화면 진입 한 번에
            // 원본 N장을 선서명·로딩하게 되므로 명시적으로 실패하고 개별 placeholder를 보인다.
            AdminPhotoAccessMode.PREVIEW -> photoStorage.presignView(
                photo.previewKey ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND),
            )
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
            expiresAt = ZonedDateTime.now(clock) + when (request.mode) {
                AdminPhotoAccessMode.PREVIEW -> storageProperties.viewUrlTtl
                AdminPhotoAccessMode.VIEW, AdminPhotoAccessMode.DOWNLOAD -> storageProperties.originalUrlTtl
            },
        )
    }
}
