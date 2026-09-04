package com.soma.wes.admin.resource

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.resource.dto.AdminPhotoAccessMode
import com.soma.wes.admin.resource.dto.AdminPhotoAccessRequest
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import com.soma.wes.admin.resource.service.AdminPhotoAccessService
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.service.PhotoStorage
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AdminPhotoAccessServiceTest {

    private val repository = mock<AdminResourceRepository>()
    private val photoStorage = mock<PhotoStorage>()
    private val auditService = mock<AdminAuditService>()
    private val clock = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val properties = StorageProperties(
        bucket = "test-bucket",
        uploadUrlTtl = Duration.ofMinutes(30),
        viewUrlTtl = Duration.ofMinutes(15),
        originalUrlTtl = Duration.ofHours(1),
        maxBatchSize = 1000,
    )
    private val service = AdminPhotoAccessService(repository, photoStorage, properties, auditService, clock)

    @Test
    fun `원본 다운로드는 attachment 서명 URL을 발급하고 감사 로그를 남긴다`() {
        whenever(repository.findPhotoOriginal(42)).thenReturn(
            AdminResourceRepository.PhotoOriginal(42, "wedding final.jpg", "galleries/7/photo.jpg"),
        )
        whenever(photoStorage.presignDownload("galleries/7/photo.jpg", "wedding final.jpg"))
            .thenReturn("https://signed.example/download")

        val response = service.access(
            actorAdminId = 9,
            photoId = 42,
            request = AdminPhotoAccessRequest("고객 요청 원본 확인", AdminPhotoAccessMode.DOWNLOAD),
            sourceAddress = "127.0.0.1",
        )

        assertThat(response.url).isEqualTo("https://signed.example/download")
        assertThat(response.expiresAt.toInstant()).isEqualTo(Instant.parse("2026-08-25T13:00:00Z"))
        verify(auditService).recordEvent(
            action = eq(AdminAuditAction.ORIGINAL_PHOTO_DOWNLOADED),
            outcome = any(),
            actorAdminId = eq(9),
            actorUsername = isNull(),
            targetType = any(),
            targetId = eq("42"),
            targetLabel = eq("wedding final.jpg"),
            reason = eq("고객 요청 원본 확인"),
            sourceAddress = eq("127.0.0.1"),
            changedFields = any(),
            correlationId = isNull(),
            impersonationSessionId = isNull(),
        )
    }

    @Test
    fun `목업 preview는 파생 키만 짧게 서명하고 별도 감사 로그를 남긴다`() {
        whenever(repository.findPhotoOriginal(43)).thenReturn(
            AdminResourceRepository.PhotoOriginal(
                43,
                "wedding preview.jpg",
                "galleries/7/original.jpg",
                "galleries/7/previews/preview.jpg",
            ),
        )
        whenever(photoStorage.presignView("galleries/7/previews/preview.jpg"))
            .thenReturn("https://signed.example/preview")

        val response = service.access(
            actorAdminId = 9,
            photoId = 43,
            request = AdminPhotoAccessRequest("앨범 목업 배치 확인", AdminPhotoAccessMode.PREVIEW),
            sourceAddress = "127.0.0.1",
        )

        assertThat(response.url).isEqualTo("https://signed.example/preview")
        assertThat(response.expiresAt.toInstant()).isEqualTo(Instant.parse("2026-08-25T12:15:00Z"))
        verify(photoStorage, never()).presignOriginal(any())
        verify(auditService).recordEvent(
            action = eq(AdminAuditAction.PHOTO_PREVIEW_VIEWED),
            outcome = any(),
            actorAdminId = eq(9),
            actorUsername = isNull(),
            targetType = any(),
            targetId = eq("43"),
            targetLabel = eq("wedding preview.jpg"),
            reason = eq("앨범 목업 배치 확인"),
            sourceAddress = eq("127.0.0.1"),
            changedFields = any(),
            correlationId = isNull(),
            impersonationSessionId = isNull(),
        )
    }
}
