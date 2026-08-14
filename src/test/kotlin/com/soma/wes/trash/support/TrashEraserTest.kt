package com.soma.wes.trash.support

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.trash.RecordingTrashPhotoStorage
import com.soma.wes.trash.repository.TrashRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 보관 기간 만료 purge의 규칙을 확인한다 — 무엇을 걷고, 무엇을 남기고, 실패하면 어떻게 되는가.
 *
 * 시계를 고정하는 대신 `deleted_at`을 상대 시각으로 심는다. [Photo.moveToTrash]가 시각을
 * 받으므로 "4일 전에 지웠다"는 상태를 그대로 만들 수 있다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, TrashEraserTest.RecordingStorageConfig::class)
class TrashEraserTest @Autowired constructor(
    private val trashEraser: TrashEraser,
    private val trashRepository: TrashRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val photoStorage: RecordingTrashPhotoStorage,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        studioRepository.deleteAllInBatch()
        photoStorage.reset()
    }

    @Test
    fun `보관 기간이 지난 것만 걷는다`() {
        val expiredGalleryId = createGallery()
        savePhoto(expiredGalleryId)
        trashGallery(expiredGalleryId, ZonedDateTime.now().minusDays(4))

        val freshGalleryId = createGallery()
        val freshPhoto = savePhoto(freshGalleryId)
        freshPhoto.moveToTrash(ZonedDateTime.now().minusDays(1))
        photoRepository.saveAndFlush(freshPhoto)

        trashEraser.purgeExpired()

        assertEquals(0L, countGalleryRows(expiredGalleryId))
        // 하루밖에 안 된 사진은 남는다 — 아직 복원할 수 있어야 한다.
        assertEquals(
            listOf(freshPhoto.requiredId),
            trashRepository.findTrashedPhotos(freshGalleryId).map { it.photoId },
        )
    }

    @Test
    fun `갤러리 purge는 휴지통에 있던 사진의 원본까지 걷는다`() {
        val galleryId = createGallery()
        val hidden = savePhoto(galleryId)
        hidden.moveToTrash(ZonedDateTime.now().minusDays(5))
        photoRepository.saveAndFlush(hidden)
        val alive = savePhoto(galleryId)
        trashGallery(galleryId, ZonedDateTime.now().minusDays(4))

        trashEraser.purgeExpired()

        val deleted = photoStorage.deletedKeys()
        assertTrue(hidden.storageKey in deleted, "휴지통에 있던 사진의 원본이 지워지지 않았다")
        assertTrue(alive.storageKey in deleted, "살아 있던 사진의 원본이 지워지지 않았다")
        assertEquals(0L, countGalleryRows(galleryId))
    }

    @Test
    fun `S3 삭제가 실패하면 행을 남겨 다음 시각에 다시 걷는다`() {
        val galleryId = createGallery()
        savePhoto(galleryId)
        trashGallery(galleryId, ZonedDateTime.now().minusDays(4))

        photoStorage.failDelete = true
        trashEraser.purgeExpired()
        // 행이 남아 있어야 다음 purge가 같은 대상을 다시 집는다. DB를 먼저 지우면 이 경로가 없다.
        assertEquals(1L, countGalleryRows(galleryId))

        photoStorage.failDelete = false
        trashEraser.purgeExpired()
        assertEquals(0L, countGalleryRows(galleryId))
    }

    // --- helpers ---

    private fun createGallery(): Long {
        val studio = studioRepository.save(
            Studio(
                userId = sequence.incrementAndGet(),
                name = "테스트 스튜디오",
                galleryUrl = "eraser-${sequence.incrementAndGet()}",
            ),
        )
        return checkNotNull(galleryRepository.save(Gallery(studioId = checkNotNull(studio.id), title = "본식")).id)
    }

    private fun savePhoto(galleryId: Long): Photo =
        photoRepository.saveAndFlush(
            Photo(
                galleryId = galleryId,
                storageKey = "galleries/$galleryId/photo-${sequence.incrementAndGet()}.jpg",
                originalFileName = "photo.jpg",
                contentType = "image/jpeg",
                displayOrder = 0,
            ),
        )

    private fun trashGallery(galleryId: Long, at: ZonedDateTime) {
        val gallery = galleryRepository.findById(galleryId).orElseThrow()
        gallery.moveToTrash(at)
        galleryRepository.saveAndFlush(gallery)
    }

    private fun countGalleryRows(galleryId: Long): Long =
        checkNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM galleries WHERE id = ?", Long::class.java, galleryId))

    @TestConfiguration(proxyBeanMethods = false)
    class RecordingStorageConfig {

        @Bean
        @Primary
        fun recordingTrashPhotoStorage(): RecordingTrashPhotoStorage = RecordingTrashPhotoStorage()
    }
}
