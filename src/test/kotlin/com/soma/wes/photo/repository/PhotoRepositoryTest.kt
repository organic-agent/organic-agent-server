package com.soma.wes.photo.repository

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.domain.PhotoStorageOwnership
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import java.time.Instant
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 벡터 매핑과 마이그레이션이 함께 걸려 있는 자리다.
 *
 * `vector(768)` 컬럼도 `CREATE EXTENSION vector`도 Flyway가 만든다. Hibernate가 어긋난 것을
 * 만들었다면 `ddl-auto: validate`가 컨텍스트 로딩에서 이미 멈춰 세운다.
 */
@DataJpaTest
@Import(TestcontainersConfiguration::class, TimeConfig::class)
class PhotoRepositoryTest @Autowired constructor(
    private val photoRepository: PhotoRepository,
    private val galleryRepository: GalleryRepository,
    private val studioRepository: StudioRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    private var sequence = 0L

    private fun photo(galleryId: Long, index: Int) = Photo(
        galleryId = galleryId,
        storageKey = "galleries/$galleryId/photo-$index.jpg",
        originalFileName = "photo-$index.jpg",
        contentType = "image/jpeg",
        displayOrder = index,
    )

    private fun createGallery(): Long {
        sequence++
        val studio = studioRepository.save(
            Studio(userId = sequence, name = "테스트 스튜디오", galleryUrl = "photo-repository-$sequence"),
        )
        return checkNotNull(
            galleryRepository.save(
                Gallery(studioId = checkNotNull(studio.id), title = "테스트 갤러리"),
            ).id,
        )
    }

    @Test
    fun `768차원 벡터를 저장하고 그대로 읽어온다`() {
        val galleryId = createGallery()
        val saved = photoRepository.save(photo(galleryId, 1))
        saved.applyEmbedding(FloatArray(Photo.EMBEDDING_DIMENSION) { it * 0.001f })
        photoRepository.flush()

        val found = photoRepository.findById(saved.requiredId).orElseThrow()

        assertEquals(Photo.EMBEDDING_DIMENSION, found.embedding?.size)
        assertEquals(0.5f, found.embedding!![500], 1e-6f)
        assertEquals(PhotoStatus.EMBEDDED, found.status)
    }

    @Test
    fun `클러스터 준비 집계는 PENDING도 미완료로 센다`() {
        // 클러스터는 모든 사진의 임베딩이 준비되기 전까지 열리지 않아야 하므로,
        // 아직 업로드되지 않은 PENDING도 embedding이 비어 있는 사진으로 센다.
        val galleryId = createGallery()
        val embedded = photoRepository.save(photo(galleryId, 1))
        embedded.applyEmbedding(FloatArray(Photo.EMBEDDING_DIMENSION))
        photoRepository.save(photo(galleryId, 2))
        photoRepository.save(photo(galleryId, 3))
        photoRepository.flush()

        assertEquals(2, photoRepository.countByGalleryIdAndEmbeddingIsNull(galleryId))
        assertEquals(3, photoRepository.countByGalleryId(galleryId))
    }

    @Test
    fun `상태별로 센다`() {
        val galleryId = createGallery()
        photoRepository.save(photo(galleryId, 1)).markUploaded()
        photoRepository.save(photo(galleryId, 2))
        photoRepository.flush()

        assertEquals(1, photoRepository.countByGalleryIdAndStatus(galleryId, PhotoStatus.UPLOADED))
        assertEquals(1, photoRepository.countByGalleryIdAndStatus(galleryId, PhotoStatus.PENDING))
    }

    @Test
    fun `구버전 INSERT는 일반 갤러리와 소유 사진 기본값을 사용한다`() {
        val existingGalleryId = createGallery()
        val studioId = galleryRepository.findById(existingGalleryId).orElseThrow().studioId
        val galleryId = checkNotNull(
            jdbcTemplate.queryForObject(
                """
                    INSERT INTO galleries (studio_id, title, status)
                    VALUES (?, ?, 'DRAFT')
                    RETURNING id
                """.trimIndent(),
                Long::class.java,
                studioId,
                "구버전 갤러리",
            ),
        )
        val photoId = checkNotNull(
            jdbcTemplate.queryForObject(
                """
                    INSERT INTO photos (
                        gallery_id, storage_key, original_file_name, display_order, status, content_type
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING id
                """.trimIndent(),
                Long::class.java,
                galleryId,
                "galleries/$galleryId/legacy.jpg",
                "legacy.jpg",
                0,
                "PENDING",
                "image/jpeg",
            ),
        )

        assertEquals("NORMAL", galleryRepository.findById(galleryId).orElseThrow().galleryType.name)
        assertEquals(PhotoStorageOwnership.GALLERY, photoRepository.findById(photoId).orElseThrow().storageOwnership)
    }

    @Test
    fun `임베딩 실행 대상 집계는 PENDING과 공유 템플릿을 제외한다`() {
        val galleryId = createGallery()
        photoRepository.save(photo(galleryId, 1))
        photoRepository.save(photo(galleryId, 2).also { it.markUploaded() })
        photoRepository.save(photo(galleryId, 3).also {
            it.applyEmbedding(FloatArray(Photo.EMBEDDING_DIMENSION).also { vector -> vector[0] = 1f })
        })
        photoRepository.save(
            Photo.createSharedTemplate(
                galleryId = galleryId,
                storageKey = "mock-gallery/v1/originals/a.jpg",
                previewKey = "mock-gallery/v1/previews/a.jpg",
                originalFileName = "a.jpg",
                contentType = "image/jpeg",
                displayOrder = 4,
                embedding = FloatArray(Photo.EMBEDDING_DIMENSION).also { it[0] = 1f },
            ),
        )
        photoRepository.flush()

        assertEquals(
            2,
            photoRepository.countByGalleryIdAndStorageOwnershipAndStatusNot(
                galleryId,
                PhotoStorageOwnership.GALLERY,
                PhotoStatus.PENDING,
            ),
        )
        assertEquals(
            1,
            photoRepository.countByGalleryIdAndStorageOwnershipAndStatusNotAndEmbeddingIsNull(
                galleryId,
                PhotoStorageOwnership.GALLERY,
                PhotoStatus.PENDING,
            ),
        )
    }

    @Test
    fun `같은 갤러리 안에서는 같은 저장 위치를 두 사진이 나눠 가질 수 없다`() {
        // 일반 업로드가 같은 key를 두 번 발급하면 나중의 PUT이 앞선 원본을 덮어쓴다.
        val galleryId = createGallery()
        photoRepository.save(photo(galleryId, 1))
        photoRepository.flush()

        val duplicated = assertFailsWith<DataIntegrityViolationException> {
            photoRepository.saveAndFlush(photo(galleryId, 1))
        }

        assertEquals("uk_photos_gallery_id_storage_key", constraintName(duplicated))
    }

    @Test
    fun `다른 갤러리는 같은 공유 템플릿 저장 위치를 참조할 수 있다`() {
        val firstGalleryId = createGallery()
        val secondGalleryId = createGallery()
        val embedding = FloatArray(Photo.EMBEDDING_DIMENSION).also { it[0] = 1f }
        val first = Photo.createSharedTemplate(
            galleryId = firstGalleryId,
            storageKey = "mock-gallery/v1/originals/a.jpg",
            previewKey = "mock-gallery/v1/previews/a.jpg",
            originalFileName = "a.jpg",
            contentType = "image/jpeg",
            displayOrder = 0,
            embedding = embedding,
        )
        val second = Photo.createSharedTemplate(
            galleryId = secondGalleryId,
            storageKey = first.storageKey,
            previewKey = first.previewKey!!,
            originalFileName = "a.jpg",
            contentType = "image/jpeg",
            displayOrder = 0,
            embedding = embedding,
        )

        photoRepository.saveAllAndFlush(listOf(first, second))

        assertEquals(2, photoRepository.findAll().count { it.storageKey == first.storageKey })
    }

    @Test
    fun `일반 사진 저장 위치는 다른 갤러리에서도 중복할 수 없다`() {
        val firstGalleryId = createGallery()
        val secondGalleryId = createGallery()
        val sharedByMistake = "galleries/$firstGalleryId/shared-by-mistake.jpg"
        photoRepository.save(
            Photo(
                galleryId = firstGalleryId,
                storageKey = sharedByMistake,
                originalFileName = "first.jpg",
                contentType = "image/jpeg",
            ),
        )
        photoRepository.flush()

        val duplicated = assertFailsWith<DataIntegrityViolationException> {
            photoRepository.saveAndFlush(
                Photo(
                    galleryId = secondGalleryId,
                    storageKey = sharedByMistake,
                    originalFileName = "second.jpg",
                    contentType = "image/jpeg",
                ),
            )
        }

        assertEquals("uk_photos_gallery_owned_storage_key", constraintName(duplicated))
    }

    @Test
    fun `일반 사진은 공유 템플릿 namespace를 사용할 수 없다`() {
        val galleryId = createGallery()
        val invalid = assertFailsWith<DataIntegrityViolationException> {
            photoRepository.saveAndFlush(
                Photo(
                    galleryId = galleryId,
                    storageKey = "mock-gallery/v1/originals/not-shared.jpg",
                    originalFileName = "not-shared.jpg",
                    contentType = "image/jpeg",
                ),
            )
        }

        assertEquals("ck_photos_storage_namespace", constraintName(invalid))
    }

    @Test
    fun `공유 템플릿은 업로드 URL 만료시각을 가질 수 없다`() {
        val galleryId = createGallery()
        val invalid = Photo.createSharedTemplate(
            galleryId = galleryId,
            storageKey = "mock-gallery/v1/originals/with-upload-url.jpg",
            previewKey = "mock-gallery/v1/previews/with-upload-url.jpg",
            originalFileName = "with-upload-url.jpg",
            contentType = "image/jpeg",
            displayOrder = 0,
            embedding = FloatArray(Photo.EMBEDDING_DIMENSION).also { it[0] = 1f },
        ).also {
            it.recordUploadUrlExpiration(Instant.now().plusSeconds(60))
        }

        val exception = assertFailsWith<DataIntegrityViolationException> {
            photoRepository.saveAndFlush(invalid)
        }

        assertEquals("ck_photos_shared_template_no_upload_url", constraintName(exception))
    }

    @Test
    fun `공유 템플릿은 미리보기 없이 준비 완료로 저장할 수 없다`() {
        val galleryId = createGallery()
        val invalid = Photo(
            galleryId = galleryId,
            storageKey = "mock-gallery/v1/originals/missing-preview.jpg",
            storageOwnership = PhotoStorageOwnership.SHARED_TEMPLATE,
            originalFileName = "missing-preview.jpg",
            contentType = "image/jpeg",
        ).also {
            it.applyEmbedding(FloatArray(Photo.EMBEDDING_DIMENSION).also { vector -> vector[0] = 1f })
        }

        val exception = assertFailsWith<DataIntegrityViolationException> {
            photoRepository.saveAndFlush(invalid)
        }

        assertEquals("ck_photos_shared_template_ready", constraintName(exception))
    }

    @Test
    fun `다른 갤러리의 사진은 id로 지정해도 딸려오지 않는다`() {
        val mineGalleryId = createGallery()
        val othersGalleryId = createGallery()
        val mine = photoRepository.save(photo(mineGalleryId, 1))
        val others = photoRepository.save(photo(othersGalleryId, 1))
        photoRepository.flush()

        val found = photoRepository.findAllByGalleryIdAndIdIn(
            mineGalleryId,
            listOf(mine.requiredId, others.requiredId),
        )

        assertEquals(listOf(mine.requiredId), found.map { it.requiredId })
    }

    @Test
    fun `페이지를 나눠 조회한다`() {
        val galleryId = createGallery()
        repeat(3) { photoRepository.save(photo(galleryId, it)) }
        photoRepository.flush()

        val page = photoRepository.findAllByGalleryId(galleryId, PageRequest.of(0, 2))

        assertEquals(2, page.content.size)
        assertEquals(3, page.totalElements)
        assertTrue(page.hasNext())
    }

    private fun constraintName(exception: DataIntegrityViolationException): String? =
        generateSequence<Throwable>(exception) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .mapNotNull { it.constraintName }
            .firstOrNull()
}
