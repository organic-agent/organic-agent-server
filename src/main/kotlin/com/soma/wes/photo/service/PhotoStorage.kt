package com.soma.wes.photo.service

import com.soma.wes.photo.config.StorageProperties
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.util.UUID

/**
 * 원본 사진이 사는 버킷으로 가는 서명 URL을 만든다.
 *
 * 버킷은 전면 비공개다(퍼블릭 액세스 차단). 브라우저가 객체를 올리거나 내려받는 방법은 여기서
 * 만드는 서명 URL 하나뿐이고, 그래서 이미지 바이트가 이 서버를 통과하지 않을 수 있다.
 *
 * 서명은 순수 로컬 계산이라 AWS를 호출하지 않는다. 다만 서명에 쓰인 자격증명(운영에서는
 * 인스턴스 롤의 임시 자격증명)이 만료되면 TTL이 남아 있어도 URL이 함께 죽는다.
 */
@Component
class PhotoStorage(
    private val s3Presigner: S3Presigner,
    private val properties: StorageProperties,
) {

    /**
     * 업로드 목적지를 정한다.
     *
     * 원본 파일명은 컬럼에 따로 남기고 키에는 UUID를 쓴다. 파일명을 그대로 키에 넣으면
     * 같은 이름의 사진이 서로를 덮어쓰고, 한글·공백이 섞인 이름은 인코딩 문제를 만든다.
     */
    fun buildKey(galleryId: Long, originalFileName: String): String {
        val extension = originalFileName.substringAfterLast('.', "").lowercase()
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return "galleries/$galleryId/${UUID.randomUUID()}$suffix"
    }

    /** 프론트가 이 URL로 S3에 직접 PUT 한다. 같은 Content-Type으로 보내야 서명이 맞는다. */
    fun presignUpload(key: String, contentType: String): String {
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

    /** `<img src>`에 그대로 꽂을 수 있는 조회용 URL. */
    fun presignView(key: String): String {
        val getRequest = GetObjectRequest.builder()
            .bucket(properties.bucket)
            .key(key)
            .build()

        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(properties.viewUrlTtl)
            .getObjectRequest(getRequest)
            .build()

        return s3Presigner.presignGetObject(presignRequest).url().toExternalForm()
    }
}
