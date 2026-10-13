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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Error
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.function.Consumer

class S3PhotoStorageTest {

    private val s3Client = mock<S3Client>()
    private val s3Presigner = mock<S3Presigner>()
    private val storage = S3PhotoStorage(
        s3Client = s3Client,
        s3Presigner = s3Presigner,
        properties = properties(),
    )

    private fun properties() = StorageProperties(
        bucket = "test-bucket",
        uploadUrlTtl = Duration.ofMinutes(30),
        viewUrlTtl = Duration.ofMinutes(15),
        originalUrlTtl = Duration.ofHours(1),
        maxBatchSize = 500,
        maxUploadBytes = 20L * 1024 * 1024,
        pendingFirstCheckAfter = Duration.ofMinutes(1),
        pendingRecheckEvery = Duration.ofMinutes(10),
        pendingGiveUpAfter = Duration.ofHours(24),
    )

    @Nested
    @DisplayName("키를 만들 때")
    inner class BuildKey {

        @Test
        fun `갤러리 키 공간 galleries 아래에 둔다`() {
            // when
            val key = storage.buildKey(7, "IMG_0001.JPG")

            // then
            assertThat(storage.galleryPrefix(7)).isEqualTo("galleries/7/")
            assertThat(key).startsWith("galleries/7/").endsWith(".jpg")
        }
    }

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

        @Test
        fun `체크섬 값을 받으면 그 값을 x-amz-checksum-crc32c 로 서명하고 알고리즘만 따로 지정하지 않는다`() {
            // 값 없이 checksumAlgorithm(CRC32_C)만 지정하면 SDK가 자기 내부 헤더(x-amz-sdk-checksum-algorithm)를 서명에
            // 넣고 체크섬 헤더는 서명하지 않아, 브라우저가 무엇을 보내든 S3가 403으로 거절한다.
            // given
            stubPresigner()

            // when
            storage.presignUpload("galleries/1/photo.jpg", "image/jpeg", contentLength = 1024, crc32c = "wdRDgw==")

            // then
            val request = capturedPutRequest()
            assertThat(request.checksumCRC32C()).isEqualTo("wdRDgw==")
            assertThat(request.checksumAlgorithm()).isNull()
            assertThat(request.contentLength()).isEqualTo(1024L)
        }

        @Test
        fun `체크섬과 크기를 모르는 업로드는 둘 다 서명에서 뺀다`() {
            // 보정 주석·관리자 교체처럼 크기를 미리 알 수 없는 업로드. 서명에 체크섬 관련 헤더가 하나라도 남으면 올릴 수 없다.
            // given
            stubPresigner()

            // when
            storage.presignUpload("galleries/1/retouch/annotations/a.png", "image/png")

            // then
            val request = capturedPutRequest()
            assertThat(request.checksumCRC32C()).isNull()
            assertThat(request.checksumAlgorithm()).isNull()
            assertThat(request.contentLength() as Long?).isNull()
        }

        private fun stubPresigner() {
            val signed = mock<PresignedPutObjectRequest>()
            whenever(signed.url()).thenReturn(URI("https://example.test/upload?X-Amz-Signature=test").toURL())
            whenever(signed.expiration()).thenReturn(Instant.parse("2026-08-09T03:30:00Z"))
            whenever(s3Presigner.presignPutObject(any<PutObjectPresignRequest>())).thenReturn(signed)
        }

        private fun capturedPutRequest(): PutObjectRequest {
            val requests = argumentCaptor<PutObjectPresignRequest>()
            verify(s3Presigner).presignPutObject(requests.capture())
            return requests.firstValue.putObjectRequest()
        }
    }

    @Nested
    @DisplayName("객체 존재 여부를 확인할 때")
    inner class Exists {

        @Test
        fun `HEAD가 성공하면 업로드된 객체로 판단한다`() {
            whenever(s3Client.headObject(any<Consumer<HeadObjectRequest.Builder>>()))
                .thenReturn(HeadObjectResponse.builder().build())

            assertThat(storage.exists("galleries/1/replacement.jpg")).isTrue()

            verify(s3Client).headObject(any<Consumer<HeadObjectRequest.Builder>>())
        }

        @Test
        fun `HEAD 404는 미완료 업로드로 판단한다`() {
            whenever(s3Client.headObject(any<Consumer<HeadObjectRequest.Builder>>()))
                .thenThrow(S3Exception.builder().statusCode(404).message("not found").build())

            assertThat(storage.exists("galleries/1/missing.jpg")).isFalse()
        }

        @Test
        fun `HEAD 권한 또는 서비스 실패를 객체 없음으로 숨기지 않는다`() {
            whenever(s3Client.headObject(any<Consumer<HeadObjectRequest.Builder>>()))
                .thenThrow(S3Exception.builder().statusCode(403).message("denied").build())

            assertThatThrownBy { storage.exists("galleries/1/denied.jpg") }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.STORAGE_METADATA_FAILED)
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
