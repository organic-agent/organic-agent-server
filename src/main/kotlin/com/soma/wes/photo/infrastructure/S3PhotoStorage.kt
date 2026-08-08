package com.soma.wes.photo.infrastructure

import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.service.PhotoStorage
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration
import java.util.UUID


@Component
class S3PhotoStorage(
    private val s3Presigner: S3Presigner,
    private val properties: StorageProperties,
) : PhotoStorage {

    override fun buildKey(galleryId: Long, originalFileName: String): String {
        val extension = originalFileName.substringAfterLast('.', "").lowercase()
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return "galleries/$galleryId/${UUID.randomUUID()}$suffix"
    }

    override fun presignUpload(key: String, contentType: String): String {
        val putRequest = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(key)
            .contentType(contentType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(properties.uploadUrlTtl)
            .putObjectRequest(putRequest)
            .build()

        return s3Presigner.presignPutObject(presignRequest).url().toExternalForm()
    }

    override fun presignView(key: String): String = presignGet(key, properties.viewUrlTtl)

    override fun presignOriginal(key: String): String = presignGet(key, properties.originalUrlTtl)

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
}
