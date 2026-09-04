package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.config.AdminAuthProperties
import com.soma.wes.admin.config.AdminObservabilityProperties
import com.soma.wes.admin.resource.dto.AdminSystemSettingsResponse
import com.soma.wes.analysis.config.EmbeddingProperties
import com.soma.wes.gallery.config.MockGalleryProperties
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.trash.config.TrashProperties
import org.springframework.stereotype.Service

@Service
class AdminSystemSettingsService(
    private val adminAuthProperties: AdminAuthProperties,
    private val embeddingProperties: EmbeddingProperties,
    private val storageProperties: StorageProperties,
    private val mockGalleryProperties: MockGalleryProperties,
    private val trashProperties: TrashProperties,
    private val observabilityProperties: AdminObservabilityProperties,
) {

    fun get(): AdminSystemSettingsResponse =
        AdminSystemSettingsResponse(
            sessionAbsoluteTtlSeconds = adminAuthProperties.session.absoluteTtl.seconds,
            sessionIdleTtlSeconds = adminAuthProperties.session.idleTtl.seconds,
            maxFailedLoginAttempts = adminAuthProperties.lockout.maxFailedAttempts,
            accountLockoutSeconds = adminAuthProperties.lockout.duration.seconds,
            revisionRetentionDays = AdminAuditService.REVISION_RETENTION_DAYS,
            trashRetentionDays = trashProperties.retention.toDays(),
            embeddingConfigured = embeddingProperties.isConfigured,
            uploadMaxBatchSize = storageProperties.maxBatchSize,
            uploadUrlTtlSeconds = storageProperties.uploadUrlTtl.seconds,
            viewUrlTtlSeconds = storageProperties.viewUrlTtl.seconds,
            originalUrlTtlSeconds = storageProperties.originalUrlTtl.seconds,
            mockGalleryConfigured = mockGalleryProperties.isConfigured,
            notificationConfigured = false,
            notificationInboxEnabled = true,
            featureFlags = linkedMapOf(
                "embedding" to embeddingProperties.isConfigured,
                "mockGallery" to mockGalleryProperties.isConfigured,
                "adminReadOnlyImpersonation" to true,
                "adminCascadeTrash" to true,
                "adminSelectionRevisions" to true,
                "adminPhotoReplacement" to true,
                "adminNotificationInbox" to true,
            ),
            grafanaConfigured = observabilityProperties.grafanaConfigured,
            lokiConfigured = observabilityProperties.lokiConfigured,
        )
}
