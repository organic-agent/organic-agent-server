package com.soma.wes.studio.service

import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.studio.dto.request.ExecuteStudioDeletionRequest
import com.soma.wes.studio.dto.response.StudioDeletionResponse
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.support.PhotoDeletionTarget
import com.soma.wes.studio.support.StudioDeletionPlan
import com.soma.wes.studio.support.StudioDeletionPreparation
import com.soma.wes.studio.support.StudioDeletionProcessor
import java.time.ZonedDateTime
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioDeletionServiceTest {

    private val processor = mock<StudioDeletionProcessor>()
    private val photoStorage = mock<PhotoStorage>()
    private val service = StudioDeletionService(processor, photoStorage)

    private val requestId = UUID.fromString("9be18035-f6ef-4fbb-a05d-6534cb2c86f0")
    private val request = ExecuteStudioDeletionRequest(
        confirmedGalleryUrl = "organic-studio",
        reason = "문의 WES-CS-1234에서 소유자 최종 확인",
    )

    @Test
    fun `S3 객체를 먼저 지우고 DB cascade delete를 실행한다`() {
        val plan = StudioDeletionPlan(
            requestId = requestId,
            claimToken = UUID.randomUUID(),
            studioId = 10L,
            studioUserId = 20L,
            studioGalleryUrl = "organic-studio",
            galleryIds = setOf(100L),
            photos = setOf(
                PhotoDeletionTarget(1000L, "galleries/100/original.heic", "previews/galleries/100/original.jpg"),
            ),
        )
        val response = response()
        whenever(processor.prepare(10L, 30L, requestId, "organic-studio", request.reason))
            .thenReturn(StudioDeletionPreparation.Pending(plan))
        whenever(processor.delete(plan, 30L, request.reason)).thenReturn(response)

        val result = service.execute(10L, 30L, requestId, request)

        assertEquals(response, result)
        inOrder(photoStorage, processor) {
            verify(photoStorage).deleteAll(
                setOf("galleries/100/original.heic", "previews/galleries/100/original.jpg"),
            )
            verify(processor).delete(plan, 30L, request.reason)
        }
    }

    @Test
    fun `완료된 요청을 재실행하면 S3를 다시 호출하지 않는다`() {
        val response = response()
        whenever(processor.prepare(10L, 30L, requestId, "organic-studio", request.reason))
            .thenReturn(StudioDeletionPreparation.Completed(response))

        val result = service.execute(10L, 30L, requestId, request)

        assertEquals(response, result)
        verifyNoInteractions(photoStorage)
        verify(processor, never()).delete(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `S3 삭제가 실패하면 DB cascade delete를 시작하지 않는다`() {
        val plan = StudioDeletionPlan(
            requestId = requestId,
            claimToken = UUID.randomUUID(),
            studioId = 10L,
            studioUserId = 20L,
            studioGalleryUrl = "organic-studio",
            galleryIds = setOf(100L),
            photos = setOf(PhotoDeletionTarget(1000L, "galleries/100/original.jpg", null)),
        )
        whenever(processor.prepare(10L, 30L, requestId, "organic-studio", request.reason))
            .thenReturn(StudioDeletionPreparation.Pending(plan))
        whenever(photoStorage.deleteAll(plan.objectKeys))
            .thenThrow(PhotoException(PhotoErrorCode.STORAGE_DELETE_FAILED))

        val exception = assertFailsWith<PhotoException> {
            service.execute(10L, 30L, requestId, request)
        }

        assertEquals(PhotoErrorCode.STORAGE_DELETE_FAILED, exception.errorCode)
        verify(photoStorage).deleteAll(
            setOf("galleries/100/original.jpg", "previews/galleries/100/original.jpg"),
        )
        verify(processor, never()).delete(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(processor).release(plan)
    }

    @Test
    fun `DB cascade delete가 실패하면 실행 claim을 해제한다`() {
        val plan = StudioDeletionPlan(
            requestId = requestId,
            claimToken = UUID.randomUUID(),
            studioId = 10L,
            studioUserId = 20L,
            studioGalleryUrl = "organic-studio",
            galleryIds = setOf(100L),
            photos = setOf(PhotoDeletionTarget(1000L, "galleries/100/original.jpg", null)),
        )
        whenever(processor.prepare(10L, 30L, requestId, "organic-studio", request.reason))
            .thenReturn(StudioDeletionPreparation.Pending(plan))
        whenever(processor.delete(plan, 30L, request.reason))
            .thenThrow(StudioException(StudioErrorCode.DELETION_TARGET_CHANGED))

        val exception = assertFailsWith<StudioException> {
            service.execute(10L, 30L, requestId, request)
        }

        assertEquals(StudioErrorCode.DELETION_TARGET_CHANGED, exception.errorCode)
        verify(photoStorage).deleteAll(plan.objectKeys)
        verify(processor).release(plan)
    }

    @Test
    fun `빈 사유는 컨트롤러를 거치지 않아도 거절한다`() {
        val exception = assertFailsWith<StudioException> {
            service.execute(10L, 30L, requestId, request.copy(reason = "   "))
        }

        assertEquals(StudioErrorCode.INVALID_DELETION_REQUEST, exception.errorCode)
        verifyNoInteractions(processor, photoStorage)
    }

    private fun response() = StudioDeletionResponse(
        auditId = 1L,
        requestId = requestId,
        studioId = 10L,
        galleryCount = 1,
        photoCount = 1,
        objectCount = 2,
        executedAt = ZonedDateTime.parse("2026-08-06T21:00:00+09:00[Asia/Seoul]"),
    )
}
