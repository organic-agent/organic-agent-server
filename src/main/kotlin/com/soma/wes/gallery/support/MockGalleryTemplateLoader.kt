package com.soma.wes.gallery.support

import com.soma.wes.gallery.config.MockGalleryProperties
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.photo.domain.Photo
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 클래스패스의 Mock 갤러리 manifest를 읽고 seed 가능한 형태인지 검증한다.
 *
 * 서버가 S3 객체를 내려받아 검사하지는 않는다. 이미지 바이트가 서버를 지나지 않는 원칙을
 * 지키면서, WES-22의 업로드 도구가 검증할 hash와 서버가 사용할 key·벡터 계약을 한 파일에
 * 고정한다. manifest가 잘못됐으면 일부 사진만 넣지 않고 요청 전체를 503으로 실패시킨다.
 */
@Component
class MockGalleryTemplateLoader(
    private val properties: MockGalleryProperties,
    private val resourceLoader: ResourceLoader,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val cachedTemplate: MockGalleryTemplate by lazy { loadAndValidate() }

    fun load(): MockGalleryTemplate = cachedTemplate

    private fun loadAndValidate(): MockGalleryTemplate {
        try {
            require(properties.manifestLocation.startsWith(CLASSPATH_PREFIX)) {
                "Mock 갤러리 manifest는 classpath resource여야 합니다."
            }
            val resource = resourceLoader.getResource(properties.manifestLocation)
            require(resource.exists() && resource.isReadable) {
                "Mock 갤러리 manifest를 읽을 수 없습니다: ${properties.manifestLocation}"
            }

            val template = resource.inputStream.use {
                objectMapper.readValue(it, MockGalleryTemplate::class.java)
            }
            validate(template)
            return template
        } catch (failure: Exception) {
            log.error("Mock 갤러리 manifest 준비 실패: location={}", properties.manifestLocation, failure)
            throw GalleryException(GalleryErrorCode.MOCK_GALLERY_NOT_READY)
        }
    }

    private fun validate(template: MockGalleryTemplate) {
        require(template.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "지원하지 않는 manifest schemaVersion입니다: ${template.schemaVersion}"
        }
        require(TEMPLATE_VERSION.matches(template.templateVersion)) {
            "templateVersion 형식이 올바르지 않습니다."
        }
        require(template.embeddingModel == EMBEDDING_MODEL) {
            "임베딩 모델이 현재 Lambda와 다릅니다: ${template.embeddingModel}"
        }
        require(template.embeddingDimension == Photo.EMBEDDING_DIMENSION) {
            "임베딩 차원이 현재 DB와 다릅니다: ${template.embeddingDimension}"
        }
        require(template.photos.size in 1..MAX_PHOTO_COUNT) {
            "Mock 갤러리 사진 수는 1..$MAX_PHOTO_COUNT 범위여야 합니다."
        }

        val storageKeys = mutableSetOf<String>()
        val previewKeys = mutableSetOf<String>()
        val displayOrders = mutableSetOf<Int>()
        template.photos.forEach { photo ->
            validatePhoto(template.templateVersion, photo)
            require(storageKeys.add(photo.storageKey)) { "storageKey가 중복됩니다: ${photo.storageKey}" }
            require(previewKeys.add(photo.previewKey)) { "previewKey가 중복됩니다: ${photo.previewKey}" }
            require(displayOrders.add(photo.displayOrder)) { "displayOrder가 중복됩니다: ${photo.displayOrder}" }
        }
        require(displayOrders == (0 until template.photos.size).toSet()) {
            "displayOrder는 0부터 빠짐없이 이어져야 합니다."
        }
    }

    private fun validatePhoto(templateVersion: String, photo: MockGalleryTemplatePhoto) {
        val originalPrefix = "mock-gallery/$templateVersion/originals/"
        val previewPrefix = "mock-gallery/$templateVersion/previews/"
        require(isSafeKey(photo.storageKey, originalPrefix)) { "안전하지 않은 storageKey입니다." }
        require(isSafeKey(photo.previewKey, previewPrefix) && photo.previewKey.endsWith(".jpg")) {
            "안전하지 않은 previewKey입니다."
        }
        require(
            photo.originalFileName.isNotBlank() &&
                photo.originalFileName.length <= MAX_FILE_NAME_LENGTH &&
                photo.originalFileName.none(Char::isISOControl),
        ) {
            "originalFileName이 비었거나 너무 깁니다."
        }
        require(CONTENT_TYPE.matches(photo.contentType)) {
            "contentType은 100자 이하의 소문자 image 타입이어야 합니다."
        }
        require(SHA256.matches(photo.originalSha256) && SHA256.matches(photo.previewSha256)) {
            "SHA-256은 64자리 소문자 16진수여야 합니다."
        }
        require(photo.embedding.size == Photo.EMBEDDING_DIMENSION) {
            "임베딩 벡터 차원이 올바르지 않습니다."
        }
        require(photo.embedding.all(Float::isFinite)) { "임베딩 벡터에 유한하지 않은 값이 있습니다." }

        val norm = sqrt(photo.embedding.sumOf { value -> value.toDouble() * value.toDouble() })
        require(abs(norm - 1.0) <= NORMALIZATION_TOLERANCE) {
            "임베딩 벡터가 L2 정규화되지 않았습니다."
        }
    }

    private fun isSafeKey(key: String, expectedPrefix: String): Boolean =
        key.length <= MAX_STORAGE_KEY_LENGTH &&
            key.startsWith(expectedPrefix) &&
            key.length > expectedPrefix.length &&
            key == key.trim() &&
            SAFE_KEY.matches(key) &&
            !key.contains("..") &&
            !key.contains("//")

    companion object {
        private const val CLASSPATH_PREFIX = "classpath:"
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val EMBEDDING_MODEL = "facebook/dinov2-base"
        private const val MAX_PHOTO_COUNT = 1_000
        private const val MAX_FILE_NAME_LENGTH = 255
        private const val MAX_STORAGE_KEY_LENGTH = 500
        private const val NORMALIZATION_TOLERANCE = 0.001
        private val TEMPLATE_VERSION = Regex("^[a-z0-9][a-z0-9._-]{0,49}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val SAFE_KEY = Regex("^[A-Za-z0-9/_.-]+$")
        private val CONTENT_TYPE = Regex("^image/[a-z0-9][a-z0-9.+-]{0,93}$")
    }
}
