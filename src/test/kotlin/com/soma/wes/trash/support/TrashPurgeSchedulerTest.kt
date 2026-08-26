package com.soma.wes.trash.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.RecordingTrashPhotoStorage
import com.soma.wes.trash.config.TrashProperties
import com.soma.wes.trash.repository.TrashRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * 스케줄러 진입점([TrashPurgeScheduler.purge])이 [TrashEraser.purgeExpired]로 이어지는지만
 * 확인한다. purge 규칙 자체(무엇을 걷고 남기는가, 실패 시 재시도)는 [TrashEraserTest]가
 * 소유한다. `@Scheduled` 주기는 검증 대상이 아니다.
 */
@IntegrationTest
@Import(TrashPurgeSchedulerTest.RecordingStorageConfig::class)
class TrashPurgeSchedulerTest @Autowired constructor(
    private val trashPurgeScheduler: TrashPurgeScheduler,
    private val trashRepository: TrashRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val photoStorage: RecordingTrashPhotoStorage,
    private val trashProperties: TrashProperties,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun resetStorage() {
        // 데이터는 DatabaseCleaner가 걷어가지만, 가짜 스토리지의 호출 기록은 DB 밖이다.
        photoStorage.reset()
    }

    @Test
    fun `보관 기간이 지난 휴지통 행만 걷는다`() {
        // given
        val expiredGalleryId = createGallery()
        savePhoto(expiredGalleryId)
        trashGallery(expiredGalleryId, expiredAt())

        val freshGalleryId = createGallery()
        val freshPhoto = savePhoto(freshGalleryId)
        freshPhoto.moveToTrash(ZonedDateTime.now().minusDays(1))
        photoRepository.saveAndFlush(freshPhoto)

        // when
        trashPurgeScheduler.purge()

        // then
        assertThat(countGalleryRows(expiredGalleryId)).isEqualTo(0L)
        assertThat(trashRepository.findTrashedPhotos(freshGalleryId).map { it.photoId })
            .isEqualTo(listOf(freshPhoto.requiredId))
    }

    // --- helpers ---

    private fun createGallery(): Long {
        val studio = studioRepository.save(
            Studio(
                userId = sequence.incrementAndGet(),
                name = "테스트 스튜디오",
                galleryUrl = "purge-scheduler-${sequence.incrementAndGet()}",
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

    private fun expiredAt(): ZonedDateTime =
        ZonedDateTime.now().minus(trashProperties.retention).minusHours(1)

    @TestConfiguration(proxyBeanMethods = false)
    class RecordingStorageConfig {

        @Bean
        @Primary
        fun recordingTrashPhotoStorage(): RecordingTrashPhotoStorage = RecordingTrashPhotoStorage()
    }
}
