package com.soma.wes.photo.repository

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
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
    fun `임베딩 대상 집계는 업로드 미완료 사진을 제외한다`() {
        // PENDING은 embedding이 비어 있어도 S3 객체가 없을 수 있어 Lambda가 읽지 않는다.
        val embedded = photoRepository.save(photo(2L, 1))
        embedded.applyEmbedding(FloatArray(Photo.EMBEDDING_DIMENSION))
        photoRepository.save(photo(2L, 2)).markUploaded()
        photoRepository.save(photo(2L, 3))
        photoRepository.flush()

        assertEquals(
            1,
            photoRepository.countByGalleryIdAndStatusNotAndEmbeddingIsNull(2L, PhotoStatus.PENDING),
        )
        assertEquals(2, photoRepository.countByGalleryIdAndStatusNot(2L, PhotoStatus.PENDING))
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
    fun `같은 저장 위치를 두 사진이 나눠 가질 수 없다`() {
        // storage_key는 S3 객체 하나를 가리킨다. 겹치면 나중에 올라온 사진이 앞선 사진을
        // 덮어써 원본이 사라진다.
        photoRepository.save(photo(4L, 1))
        photoRepository.flush()

        val duplicated = runCatching {
            photoRepository.saveAndFlush(photo(4L, 1))
        }

        assertTrue(duplicated.isFailure)
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
