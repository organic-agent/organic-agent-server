package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.MockGallerySeeder
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoMetadata
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.PresignedUploadDto
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.studio.fixture.StudioFixture
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.DatabaseClearExtension
import com.soma.wes.support.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * 템플릿 갤러리 복제 흐름을 서비스 경계에서 확인한다.
 *
 * [PhotoStorage]는 기록형 가짜로 바꾼다. presign은 로컬 서명 연산이라 다른 테스트에서 실제
 * 구현이 그대로 돌지만, copy는 진짜 S3 API 호출이라 테스트에서 실행할 수 없다.
 * 템플릿 갤러리는 프로퍼티의 고정 id로 직접 INSERT 한다 — id가 IDENTITY라 JPA로는
 * 프로퍼티(정적)와 생성 id(동적)를 맞출 수 없다.
 *
 * 커스텀 프로퍼티가 필요해 `@IntegrationTest` 대신 평문 `@SpringBootTest` 조합을 쓴다
 * (rules/test.md의 예외 규정). 어차피 `@Primary` 빈 교체로 전용 컨텍스트를 띄우는 파일이다.
 */
@SpringBootTest(properties = ["app.mock-gallery.template-gallery-id=777001"])
@Import(TestcontainersConfiguration::class, MockGalleryServiceTest.RecordingStorageConfig::class)
@ExtendWith(DatabaseClearExtension::class)
class MockGalleryServiceTest @Autowired constructor(
    private val mockGalleryService: MockGalleryService,
    private val studioFixture: StudioFixture,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val photoStorage: RecordingPhotoStorage,
) {

    @BeforeEach
    fun resetStorage() {
        // DB는 DatabaseClearExtension이 지운다. 기록형 가짜의 호출 기록은 DB 밖이라 직접 리셋한다.
        photoStorage.reset()
    }

    @Nested
    @DisplayName("템플릿을 복제할 때")
    inner class Duplicate {

        @Test
        fun `템플릿 갤러리의 사진과 임베딩을 새 갤러리로 복제한다`() {
            // given
            val templates = seedTemplate(photoCount = 2)
            val photographer = studioFixture.작가()

            // when
            val result = mockGalleryService.create(photographer.id!!, null)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.title).isEqualTo(MockGallerySeeder.DEFAULT_TITLE)
                softly.assertThat(result.status).isEqualTo(GalleryStatus.DRAFT)
                softly.assertThat(result.id).isNotEqualTo(TEMPLATE_GALLERY_ID)
            }

            val photos = photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(result.id)
            assertThat(photos).hasSize(2)
            photos.forEachIndexed { index, photo ->
                assertSoftly { softly ->
                    softly.assertThat(photo.displayOrder).isEqualTo(index)
                    softly.assertThat(photo.status).isEqualTo(PhotoStatus.EMBEDDED)
                    softly.assertThat(photo.embedding).isEqualTo(templates[index].embedding)
                    softly.assertThat(photo.metadata?.cameraMake).isEqualTo("Canon")
                    // 키는 일반 갤러리와 같은 자기 키 공간이다. 삭제·리셋 경로가 그대로 집는다.
                    softly.assertThat(photo.storageKey).startsWith("galleries/${result.id}/")
                    softly.assertThat(photo.previewKey)
                        .isEqualTo("previews/${photo.storageKey.substringBeforeLast('.')}.jpg")
                    // 업로드 URL을 발급한 적 없는 행 — 값이 있으면 휴지통 즉시 삭제가 30분간 막힌다.
                    softly.assertThat(photo.uploadUrlExpiresAt).isNull()
                }
            }
            // 사진마다 원본과 미리보기, 두 번의 복사가 일어난다.
            assertThat(photoStorage.copies).hasSize(4)
        }

        @Test
        fun `미리보기가 없는 템플릿 사진은 원본만 복사하고 null로 남긴다`() {
            // given
            seedTemplate(photoCount = 1, withPreview = false)
            val photographer = studioFixture.작가()

            // when
            val result = mockGalleryService.create(photographer.id!!, null)

            // then
            val photo = photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(result.id).single()
            assertThat(photo.previewKey).isNull()
            assertThat(photoStorage.copies).hasSize(1)
        }

        @Test
        fun `임베딩이 끝나지 않은 템플릿 사진은 복제하지 않는다`() {
            // given
            seedTemplate(photoCount = 1)
            photoRepository.save(templatePhoto(index = 9))                             // PENDING
            photoRepository.save(templatePhoto(index = 10).also { it.markUploaded() }) // UPLOADED
            val photographer = studioFixture.작가()

            // when
            val result = mockGalleryService.create(photographer.id!!, null)

            // then
            assertThat(photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(result.id)).hasSize(1)
        }

        @Test
        fun `부를 때마다 새 갤러리를 만든다`() {
            // 스튜디오당 하나라는 보장은 없다. 만들어진 갤러리가 일반 갤러리와 구분되지 않는
            // 설계의 대가이고, 버튼을 언제 감출지는 화면이 정한다.
            // given
            seedTemplate(photoCount = 1)
            val photographer = studioFixture.작가()

            // when
            val first = mockGalleryService.create(photographer.id!!, null).id
            val second = mockGalleryService.create(photographer.id!!, null).id

            // then
            assertThat(first).isNotEqualTo(second)
            val firstKeys = photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(first).map { it.storageKey }
            val secondKeys = photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(second).map { it.storageKey }
            assertThat(firstKeys).doesNotContainAnyElementsOf(secondKeys)
        }
    }

    @Nested
    @DisplayName("복제할 수 없을 때")
    inner class Unavailable {

        @Test
        fun `템플릿 갤러리가 없으면 아무것도 만들지 않는다`() {
            // 프로퍼티는 설정돼 있지만 가리키는 갤러리가 없다 — 시드 전이거나 지워진 상태다.
            // given
            val photographer = studioFixture.작가()

            // when & then
            assertThatThrownBy { mockGalleryService.create(photographer.id!!, null) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.MOCK_GALLERY_NOT_READY)

            assertThat(galleryRepository.count()).isEqualTo(0L)
            assertThat(photoStorage.copies).isEmpty()
        }

        @Test
        fun `임베딩까지 끝난 사진이 한 장도 없으면 만들지 않는다`() {
            // given
            seedTemplate(photoCount = 0)
            photoRepository.save(templatePhoto(index = 0)) // PENDING뿐
            val photographer = studioFixture.작가()

            // when & then
            assertThatThrownBy { mockGalleryService.create(photographer.id!!, null) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.MOCK_GALLERY_NOT_READY)

            assertThat(galleryRepository.count()).isEqualTo(1L) // 템플릿 갤러리뿐
        }

        @Test
        fun `복사가 중간에 실패하면 복사분과 갤러리 행을 걷어낸다`() {
            // given
            seedTemplate(photoCount = 2)
            photoStorage.failCopyAfter = 2 // 세 번째 복사부터 실패
            val photographer = studioFixture.작가()

            // when & then
            assertThatThrownBy { mockGalleryService.create(photographer.id!!, null) }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.STORAGE_COPY_FAILED)

            // 만들다 만 갤러리가 남지 않는다 — 남는 것은 템플릿 갤러리뿐이다.
            assertThat(galleryRepository.count()).isEqualTo(1L)
            assertThat(photoStorage.deleted)
                .containsExactly(photoStorage.copies[0].second, photoStorage.copies[1].second)
        }
    }

    // --- 템플릿 시드 ---

    /**
     * 운영자 스튜디오와 고정 id의 템플릿 갤러리, 임베딩까지 끝난 사진 [photoCount]장을 만든다.
     * 갤러리 행은 IDENTITY가 `BY DEFAULT`라서 명시 id로 INSERT 할 수 있다.
     */
    private fun seedTemplate(photoCount: Int, withPreview: Boolean = true): List<Photo> {
        val operator = studioFixture.작가()
        jdbcTemplate.update(
            "INSERT INTO galleries (id, studio_id, title, status, created_at, updated_at) " +
                "VALUES (?, ?, '샘플 템플릿', 'DRAFT', now(), now())",
            TEMPLATE_GALLERY_ID,
            studioRepository.findByUserId(operator.id!!)!!.requiredId,
        )
        return (0 until photoCount).map { index ->
            photoRepository.save(
                templatePhoto(index, withPreview).also { photo ->
                    photo.applyMetadata(PhotoMetadata(cameraMake = "Canon", width = 1024, height = 768))
                    photo.applyEmbedding(FloatArray(Photo.EMBEDDING_DIMENSION) { (index + 1) * 0.01f })
                },
            )
        }
    }

    private fun templatePhoto(index: Int, withPreview: Boolean = false): Photo =
        Photo(
            galleryId = TEMPLATE_GALLERY_ID,
            storageKey = "galleries/$TEMPLATE_GALLERY_ID/template-$index.png",
            originalFileName = "template-$index.png",
            contentType = "image/png",
            displayOrder = index,
        ).also { photo ->
            if (withPreview) {
                photo.previewKey = "previews/galleries/$TEMPLATE_GALLERY_ID/template-$index.jpg"
            }
        }

    companion object {
        /** `app.mock-gallery.template-gallery-id` 프로퍼티와 같은 값. */
        const val TEMPLATE_GALLERY_ID = 777001L
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RecordingStorageConfig {

        @Bean
        @Primary
        fun recordingPhotoStorage(): RecordingPhotoStorage = RecordingPhotoStorage()
    }
}

/**
 * 호출을 기록하는 [PhotoStorage]. `buildKey`는 실제 구현과 같은 규칙을 쓴다 —
 * 키 모양(`galleries/{id}/{uuid}.{ext}`)이 곧 검증 대상이라 임의 문자열로 대체할 수 없다.
 */
class RecordingPhotoStorage : PhotoStorage {

    val copies = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<String>()

    /** 이만큼 복사한 뒤부터는 실패를 주입한다. null이면 항상 성공. */
    var failCopyAfter: Int? = null

    fun reset() {
        copies.clear()
        deleted.clear()
        failCopyAfter = null
    }

    override fun buildKey(galleryId: Long, originalFileName: String): String {
        val extension = originalFileName.substringAfterLast('.', "").lowercase()
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return "galleries/$galleryId/${UUID.randomUUID()}$suffix"
    }

    override fun presignUpload(key: String, contentType: String): PresignedUploadDto =
        PresignedUploadDto(url = "https://storage.test/upload/$key", expiresAt = Instant.now().plusSeconds(1800))

    override fun presignView(key: String): String = "https://storage.test/view/$key"

    override fun presignOriginal(key: String): String = "https://storage.test/original/$key"

    override fun exists(key: String): Boolean = true

    override fun deleteAll(keys: Collection<String>) {
        deleted += keys
    }

    override fun copy(sourceKey: String, targetKey: String) {
        failCopyAfter?.let { limit ->
            if (copies.size >= limit) {
                throw PhotoException(PhotoErrorCode.STORAGE_COPY_FAILED)
            }
        }
        copies += sourceKey to targetKey
    }
}
