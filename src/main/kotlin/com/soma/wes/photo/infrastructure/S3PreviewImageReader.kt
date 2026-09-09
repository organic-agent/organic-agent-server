package com.soma.wes.photo.infrastructure

import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.service.port.PreviewImageReader
import com.soma.wes.photo.support.JpegResizer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client

@Component
class S3PreviewImageReader(
    private val s3Client: S3Client,
    private val properties: StorageProperties,
    private val resizer: JpegResizer,
) : PreviewImageReader {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun readJpeg(previewKey: String, longEdge: Int): ByteArray {
        val bytes = try {
            s3Client.getObjectAsBytes { it.bucket(properties.bucket).key(previewKey) }.asByteArray()
        } catch (e: SdkException) {
            log.error("S3 미리보기 읽기 실패: bucket={}, key={}", properties.bucket, previewKey, e)
            throw PhotoException(PhotoErrorCode.STORAGE_READ_FAILED)
        }
        return try {
            resizer.resize(bytes, longEdge)
        } catch (e: Exception) {
            log.error("미리보기 리사이즈 실패: key={}", previewKey, e)
            throw PhotoException(PhotoErrorCode.STORAGE_READ_FAILED)
        }
    }
}
