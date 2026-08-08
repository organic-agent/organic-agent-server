package com.soma.wes.photo.infrastructure

import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import java.time.Duration
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.S3Error
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class S3PhotoStorageTest {

    private val s3Client = mock<S3Client>()
    private val storage = S3PhotoStorage(
        s3Client = s3Client,
        s3Presigner = mock<S3Presigner>(),
        properties = StorageProperties(
            bucket = "test-bucket",
            uploadUrlTtl = Duration.ofMinutes(30),
            viewUrlTtl = Duration.ofMinutes(15),
            originalUrlTtl = Duration.ofHours(1),
            maxBatchSize = 1000,
        ),
    )

    @Test
    fun `삭제할 키가 없으면 S3를 호출하지 않는다`() {
        storage.deleteAll(emptySet())

        verify(s3Client, never()).deleteObjects(any<DeleteObjectsRequest>())
    }

    @Test
    fun `S3 제한에 맞춰 천 개씩 나눠 삭제한다`() {
        whenever(s3Client.deleteObjects(any<DeleteObjectsRequest>()))
            .thenReturn(DeleteObjectsResponse.builder().build())

        storage.deleteAll((1..1001).map { "galleries/1/$it.jpg" })

        val requests = argumentCaptor<DeleteObjectsRequest>()
        verify(s3Client, times(2)).deleteObjects(requests.capture())
        assertEquals(listOf(1000, 1), requests.allValues.map { it.delete().objects().size })
    }

    @Test
    fun `S3가 일부 객체 실패를 응답하면 성공으로 처리하지 않는다`() {
        whenever(s3Client.deleteObjects(any<DeleteObjectsRequest>())).thenReturn(
            DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key("galleries/1/fail.jpg").code("AccessDenied").build())
                .build(),
        )

        val exception = assertFailsWith<PhotoException> {
            storage.deleteAll(setOf("galleries/1/fail.jpg"))
        }

        assertEquals(PhotoErrorCode.STORAGE_DELETE_FAILED, exception.errorCode)
    }

    @Test
    fun `여러 배치 중 일부가 실패해도 같은 키 목록으로 재시도할 수 있다`() {
        val success = DeleteObjectsResponse.builder().build()
        val partialFailure = DeleteObjectsResponse.builder()
            .errors(S3Error.builder().key("galleries/1/1001.jpg").code("InternalError").build())
            .build()
        whenever(s3Client.deleteObjects(any<DeleteObjectsRequest>()))
            .thenReturn(success, partialFailure, success, success)
        val keys = (1..1001).map { "galleries/1/$it.jpg" }

        assertFailsWith<PhotoException> { storage.deleteAll(keys) }
        storage.deleteAll(keys)

        val requests = argumentCaptor<DeleteObjectsRequest>()
        verify(s3Client, times(4)).deleteObjects(requests.capture())
        assertEquals(listOf(1000, 1, 1000, 1), requests.allValues.map { it.delete().objects().size })
    }
}
