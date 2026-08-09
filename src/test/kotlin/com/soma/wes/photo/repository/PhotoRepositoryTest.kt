package com.soma.wes.photo.repository

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.domain.PhotoStorageOwnership
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import kotlin.test.assertEquals
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
) {

    private fun photo(galleryId: Long, index: Int) = Photo(
        galleryId = galleryId,
        storageKey = "galleries/$galleryId/photo-$index.jpg",
        originalFileName = "photo-$index.jpg",
        contentType = "image/jpeg",
        displayOrder = index,
    )

    @Test
    fun `768차원 벡터를 저장하고 그대로 읽어온다`() {
        val saved = photoRepository.save(photo(1L, 1))
        saved.applyEmbedding(FloatArray(Photo.EMBEDDING_DIMENSION) { it * 0.001f })
        photoRepository.flush()

        val found = photoRepository.findById(saved.requiredId).orElseThrow()

        assertEquals(Photo.EMBEDDING_DIMENSION, found.embedding?.size)
        assertEquals(0.5f, found.embedding!![500], 1e-6f)
        assertEquals(PhotoStatus.EMBEDDED, found.status)
    }

    @Test
    fun `임베딩이 비어 있는 사진만 센다`() {
        // 임베딩 Lambda가 대상을 고르는 기준과 같다. 중간에 죽은 실행을 다시 불러도
        // 남은 것만 이어서 하는 이유가 이 조건이다.
        val embedded = photoRepository.save(photo(2L, 1))
        embedded.applyEmbedding(FloatArray(Photo.EMBEDDING_DIMENSION))
        photoRepository.save(photo(2L, 2))
        photoRepository.save(photo(2L, 3))
        photoRepository.flush()

        assertEquals(2, photoRepository.countByGalleryIdAndEmbeddingIsNull(2L))
        assertEquals(3, photoRepository.countByGalleryId(2L))
    }

    @Test
    fun `상태별로 센다`() {
        photoRepository.save(photo(3L, 1)).markUploaded()
        photoRepository.save(photo(3L, 2))
        photoRepository.flush()

        assertEquals(1, photoRepository.countByGalleryIdAndStatus(3L, PhotoStatus.UPLOADED))
        assertEquals(1, photoRepository.countByGalleryIdAndStatus(3L, PhotoStatus.PENDING))
    }

    @Test
    fun `임베딩 실행 대상에서 PENDING과 공유 템플릿을 제외한다`() {
        val galleryId = 31L
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
        photoRepository.save(photo(4L, 1))
        photoRepository.flush()

        val duplicated = runCatching {
            photoRepository.saveAndFlush(photo(4L, 1))
        }

        assertTrue(duplicated.isFailure)
    }

    @Test
    fun `다른 갤러리는 같은 공유 템플릿 저장 위치를 참조할 수 있다`() {
        val embedding = FloatArray(Photo.EMBEDDING_DIMENSION).also { it[0] = 1f }
        val first = Photo.createSharedTemplate(
            galleryId = 41L,
            storageKey = "mock-gallery/v1/originals/a.jpg",
            previewKey = "mock-gallery/v1/previews/a.jpg",
            originalFileName = "a.jpg",
            contentType = "image/jpeg",
            displayOrder = 0,
            embedding = embedding,
        )
        val second = Photo.createSharedTemplate(
            galleryId = 42L,
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
        val sharedByMistake = "galleries/51/shared-by-mistake.jpg"
        photoRepository.save(
            Photo(
                galleryId = 51L,
                storageKey = sharedByMistake,
                originalFileName = "first.jpg",
                contentType = "image/jpeg",
            ),
        )
        photoRepository.flush()

        val duplicated = runCatching {
            photoRepository.saveAndFlush(
                Photo(
                    galleryId = 52L,
                    storageKey = sharedByMistake,
                    originalFileName = "second.jpg",
                    contentType = "image/jpeg",
                ),
            )
        }

        assertTrue(duplicated.isFailure)
    }

    @Test
    fun `일반 사진은 공유 템플릿 namespace를 사용할 수 없다`() {
        val invalid = runCatching {
            photoRepository.saveAndFlush(
                Photo(
                    galleryId = 53L,
                    storageKey = "mock-gallery/v1/originals/not-shared.jpg",
                    originalFileName = "not-shared.jpg",
                    contentType = "image/jpeg",
                ),
            )
        }

        assertTrue(invalid.isFailure)
    }

    @Test
    fun `다른 갤러리의 사진은 id로 지정해도 딸려오지 않는다`() {
        val mine = photoRepository.save(photo(5L, 1))
        val others = photoRepository.save(photo(6L, 1))
        photoRepository.flush()

        val found = photoRepository.findAllByGalleryIdAndIdIn(5L, listOf(mine.requiredId, others.requiredId))

        assertEquals(listOf(mine.requiredId), found.map { it.requiredId })
    }

    @Test
    fun `페이지를 나눠 조회한다`() {
        repeat(3) { photoRepository.save(photo(7L, it)) }
        photoRepository.flush()

        val page = photoRepository.findAllByGalleryId(7L, PageRequest.of(0, 2))

        assertEquals(2, page.content.size)
        assertEquals(3, page.totalElements)
        assertTrue(page.hasNext())
    }
}
