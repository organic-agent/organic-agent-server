package com.soma.wes.photo.infrastructure

import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.photo.dto.PresignedUploadDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID


@Component
class S3PhotoStorage(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val properties: StorageProperties,
) : PhotoStorage {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** S3 DeleteObjects 한 요청의 최대 키 수. */
        private const val MAX_DELETE_OBJECTS = 1000
    }

    override fun buildKey(galleryId: Long, originalFileName: String): String {
        val extension = originalFileName.substringAfterLast('.', "").lowercase()
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return "galleries/$galleryId/${UUID.randomUUID()}$suffix"
    }

    override fun presignUpload(key: String, contentType: String): PresignedUploadDto {
        val putRequest = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(key)
            .contentType(contentType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(properties.uploadUrlTtl)
            .putObjectRequest(putRequest)
            .build()

        val presigned = s3Presigner.presignPutObject(presignRequest)
        return PresignedUploadDto(
            url = presigned.url().toExternalForm(),
            expiresAt = presigned.expiration(),
        )
    }

    override fun presignView(key: String): String = presignGet(key, properties.viewUrlTtl)

    override fun presignOriginal(key: String): String = presignGet(key, properties.originalUrlTtl)

    override fun presignDownload(key: String, originalFileName: String): String {
        val encodedName = URLEncoder.encode(originalFileName, StandardCharsets.UTF_8).replace("+", "%20")
        val getRequest = GetObjectRequest.builder()
            .bucket(properties.bucket)
            .key(key)
            .responseContentDisposition("attachment; filename*=UTF-8''$encodedName")
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(properties.originalUrlTtl)
            .getObjectRequest(getRequest)
            .build()
        return s3Presigner.presignGetObject(presignRequest).url().toExternalForm()
    }

    /** 조회용 서명은 수명만 다르다. [presignView]와 [presignOriginal]이 쓴다. */
    private fun presignGet(key: String, ttl: Duration): String {
        val getRequest = GetObjectRequest.builder()
            .bucket(properties.bucket)
            .key(key)
            .build()

        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(getRequest)
            .build()

        return s3Presigner.presignGetObject(presignRequest).url().toExternalForm()
    }

    override fun deleteAll(keys: Collection<String>) {
        val targets = keys.filter(String::isNotBlank).distinct()
        targets.chunked(MAX_DELETE_OBJECTS).forEach { chunk ->
            val request = DeleteObjectsRequest.builder()
                .bucket(properties.bucket)
                .delete { delete ->
                    delete.objects(chunk.map { key -> ObjectIdentifier.builder().key(key).build() })
                    delete.quiet(true)
                }
                .build()

            val response = try {
                s3Client.deleteObjects(request)
            } catch (e: SdkException) {
                log.error("S3 사진 삭제 요청 실패: bucket={}, count={}", properties.bucket, chunk.size, e)
                throw PhotoException(PhotoErrorCode.STORAGE_DELETE_FAILED)
            }

            if (response.hasErrors() && response.errors().isNotEmpty()) {
                val errorCodes = response.errors().mapNotNull { it.code() }.distinct()
                log.error(
                    "S3 사진 일부 삭제 실패: bucket={}, count={}, errorCodes={}",
                    properties.bucket,
                    response.errors().size,
                    errorCodes,
                )
                throw PhotoException(PhotoErrorCode.STORAGE_DELETE_FAILED)
            }
        }
    }

    override fun copy(sourceKey: String, targetKey: String) {
        try {
            s3Client.copyObject { copy ->
                copy.sourceBucket(properties.bucket).sourceKey(sourceKey)
                copy.destinationBucket(properties.bucket).destinationKey(targetKey)
            }
        } catch (e: SdkException) {
            log.error("S3 사진 복사 실패: bucket={}, source={}, target={}", properties.bucket, sourceKey, targetKey, e)
            throw PhotoException(PhotoErrorCode.STORAGE_COPY_FAILED)
        }
    }
}
