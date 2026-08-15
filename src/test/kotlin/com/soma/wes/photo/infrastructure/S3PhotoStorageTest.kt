package com.soma.wes.photo.infrastructure

import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration
import java.time.Instant

class S3PhotoStorageTest {

    private val s3Client = mock<S3Client>()
    private val s3Presigner = mock<S3Presigner>()
    private val storage = S3PhotoStorage(
        s3Client = s3Client,
        s3Presigner = s3Presigner,
        properties = StorageProperties(
            bucket = "test-bucket",
            uploadUrlTtl = Duration.ofMinutes(30),
            viewUrlTtl = Duration.ofMinutes(15),
            originalUrlTtl = Duration.ofHours(1),
            maxBatchSize = 1000,
        ),
    )

    @Nested
    @DisplayName("업로드 URL을 서명할 때")
    inner class PresignUpload {

        @Test
        fun `업로드 서명 URL과 SDK가 계산한 실제 만료 시각을 함께 돌려준다`() {
            // given
            val expiresAt = Instant.parse("2026-08-09T03:30:00Z")
            val signed = mock<PresignedPutObjectRequest>()
            whenever(signed.url()).thenReturn(URI("https://example.test/upload?X-Amz-Signature=test").toURL())
            whenever(signed.expiration()).thenReturn(expiresAt)
            whenever(s3Presigner.presignPutObject(any<PutObjectPresignRequest>())).thenReturn(signed)

            // when
            val result = storage.presignUpload("galleries/1/photo.jpg", "image/jpeg")

            // then
            assertThat(result.url).isEqualTo("https://example.test/upload?X-Amz-Signature=test")
            assertThat(result.expiresAt).isEqualTo(expiresAt)
        }
    }

    @Nested
    @DisplayName("객체를 삭제할 때")
    inner class DeleteAll {

        @Test
        fun `삭제할 키가 없으면 S3를 호출하지 않는다`() {
            // when
            storage.deleteAll(emptySet())

            // then
            verify(s3Client, never()).deleteObjects(any<DeleteObjectsRequest>())
        }

        @Test
        fun `S3 제한에 맞춰 천 개씩 나눠 삭제한다`() {
            // given
            whenever(s3Client.deleteObjects(any<DeleteObjectsRequest>()))
                .thenReturn(DeleteObjectsResponse.builder().build())

            // when
            storage.deleteAll((1..1001).map { "galleries/1/$it.jpg" })

            // then
            val requests = argumentCaptor<DeleteObjectsRequest>()
            verify(s3Client, times(2)).deleteObjects(requests.capture())
            assertThat(requests.allValues.map { it.delete().objects().size }).isEqualTo(listOf(1000, 1))
        }

        @Test
        fun `S3가 일부 객체 실패를 응답하면 성공으로 처리하지 않는다`() {
            // given
            whenever(s3Client.deleteObjects(any<DeleteObjectsRequest>())).thenReturn(
                DeleteObjectsResponse.builder()
                    .errors(S3Error.builder().key("galleries/1/fail.jpg").code("AccessDenied").build())
                    .build(),
            )

            // when & then
            assertThatThrownBy { storage.deleteAll(setOf("galleries/1/fail.jpg")) }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.STORAGE_DELETE_FAILED)
        }

        @Test
        fun `여러 배치 중 일부가 실패해도 같은 키 목록으로 재시도할 수 있다`() {
            // given
            val success = DeleteObjectsResponse.builder().build()
            val partialFailure = DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key("galleries/1/1001.jpg").code("InternalError").build())
                .build()
            whenever(s3Client.deleteObjects(any<DeleteObjectsRequest>()))
                .thenReturn(success, partialFailure, success, success)
            val keys = (1..1001).map { "galleries/1/$it.jpg" }

            // when
            assertThatThrownBy { storage.deleteAll(keys) }.isInstanceOf(PhotoException::class.java)
            storage.deleteAll(keys)

            // then
            val requests = argumentCaptor<DeleteObjectsRequest>()
            verify(s3Client, times(4)).deleteObjects(requests.capture())
            assertThat(requests.allValues.map { it.delete().objects().size }).isEqualTo(listOf(1000, 1, 1000, 1))
        }
    }
}
