package com.soma.wes.photo.support

import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.CapturedLogs
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.RecordingTrashPhotoStorage
import com.soma.wes.trash.RecordingTrashPhotoStorageConfig
import java.sql.Timestamp
import java.time.Duration
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 완료 통보 없이 남은 PENDING 사진의 서버 보정 규칙 — 언제 S3를 보고, 있으면 어떻게, 없으면 언제까지 기다리는가.
 * 시계를 고정하는 대신 사진의 `created_at`을 과거로 옮긴다.
 */
@IntegrationTest
@Import(RecordingTrashPhotoStorageConfig::class)
class PendingUploadSweeperTest @Autowired constructor(
    private val sweeper: PendingUploadSweeper,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val photoRepository: PhotoRepository,
    private val photoStorage: RecordingTrashPhotoStorage,
    private val jdbcTemplate: JdbcTemplate,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUp() {
        fixture = galleryFixture.멤버와_열린_갤러리()
        photoStorage.reset()
    }

    @Nested
    @DisplayName("처음 확인할 때")
    inner class FirstCheck {

        @Test
        fun `발급 1분 안의 사진은 보지 않는다`() {
            // given
            photoFixture.대기중_사진(fixture.galleryId, count = 2)

            // when
            sweeper.sweep()

            // then
            assertThat(photoStorage.existsCalls).isEmpty()
        }

        @Test
        fun `1분이 지났고 객체가 있으면 UPLOADED 로 올린다`() {
            // given
            val photoIds = photoFixture.대기중_사진(fixture.galleryId, count = 2)
            age(photoIds, Duration.ofMinutes(2))

            // when
            sweeper.sweep()

            // then
            val photos = photoRepository.findAllById(photoIds)
            assertSoftly { softly ->
                softly.assertThat(photoStorage.existsCalls).hasSize(2)
                softly.assertThat(photos).allSatisfy { assertThat(it.status).isEqualTo(PhotoStatus.UPLOADED) }
            }
        }

        @Test
        fun `객체가 없으면 PENDING 으로 두고 재확인 간격 전에는 다시 보지 않는다`() {
            // given
            val photoIds = photoFixture.대기중_사진(fixture.galleryId, count = 1)
            age(photoIds, Duration.ofMinutes(2))
            photoStorage.existsAnswer = { false }

            // when
            sweeper.sweep()
            sweeper.sweep()

            // then
            val photo = photoRepository.findById(photoIds.single()).orElseThrow()
            assertSoftly { softly ->
                softly.assertThat(photoStorage.existsCalls).hasSize(1)
                softly.assertThat(photo.status).isEqualTo(PhotoStatus.PENDING)
                softly.assertThat(photo.deletedAt).isNull()
            }
        }
    }

    @Nested
    @DisplayName("다시 확인할 때")
    inner class Recheck {

        @Test
        fun `마지막 확인 뒤 재확인 간격이 지나면 다시 본다`() {
            // given — 30분 전에 발급, 20분 전에 마지막으로 확인
            val photoIds = photoFixture.대기중_사진(fixture.galleryId, count = 1)
            age(photoIds, Duration.ofMinutes(30), lastCheckedAgo = Duration.ofMinutes(20))
            photoStorage.existsAnswer = { false }

            // when
            sweeper.sweep()

            // then
            assertThat(photoStorage.existsCalls).hasSize(1)
        }

        @Test
        fun `24시간이 지나도 없으면 휴지통으로 보낸다`() {
            // given
            val photoIds = photoFixture.대기중_사진(fixture.galleryId, count = 1)
            age(photoIds, Duration.ofHours(25), lastCheckedAgo = Duration.ofMinutes(20))
            photoStorage.existsAnswer = { false }

            // when
            sweeper.sweep()

            // then — @SQLRestriction 이 걸러 JPA 조회에는 나타나지 않는다
            assertSoftly { softly ->
                softly.assertThat(photoRepository.findById(photoIds.single())).isEmpty
                softly.assertThat(deletedAtOf(photoIds.single())).isNotNull()
            }
        }

        @Test
        fun `24시간이 지났어도 객체가 있으면 버리지 않고 올린다`() {
            // PUT 은 됐는데 통보만 못 한 사진은 버려지면 안 된다.
            // given
            val photoIds = photoFixture.대기중_사진(fixture.galleryId, count = 1)
            age(photoIds, Duration.ofHours(25), lastCheckedAgo = Duration.ofMinutes(20))

            // when
            sweeper.sweep()

            // then
            val photo = photoRepository.findById(photoIds.single()).orElseThrow()
            assertThat(photo.status).isEqualTo(PhotoStatus.UPLOADED)
        }
    }

    @Nested
    @DisplayName("로그를 남길 때")
    inner class Logging {

        @Test
        fun `올린 사진과 버린 사진의 번호를 스윕 traceId 와 함께 한 줄로 남긴다`() {
            // given — 통보 없이 올라온 1장, 24시간이 지나도 오지 않은 1장
            val arrived = photoFixture.대기중_사진(fixture.galleryId, count = 1).single()
            val lost = photoFixture.대기중_사진(fixture.galleryId, count = 1).single()
            age(listOf(arrived), Duration.ofMinutes(2))
            age(listOf(lost), Duration.ofHours(25), lastCheckedAgo = Duration.ofMinutes(20))
            val lostKey = photoRepository.findById(lost).orElseThrow().storageKey
            photoStorage.existsAnswer = { key -> key != lostKey }

            // when
            val sweep = CapturedLogs(PendingUploadSweeper::class).use { logs ->
                sweeper.sweep()
                logs.eventsOf("upload.sweep").single()
            }

            // then — "유실 의심" 알림이 trashed 를 보고, 어느 사진인지는 이 줄에서 찾는다
            assertSoftly { softly ->
                softly.assertThat(sweep.formattedMessage).isEqualTo(
                    "event=upload.sweep checked=2 uploaded=1 trashed=1 waiting=0 uploadedPhotoIds=$arrived trashedPhotoIds=$lost",
                )
                softly.assertThat(sweep.mdcPropertyMap[LogContext.TRACE_ID]).startsWith(LogContext.SWEEP_TRACE_PREFIX)
            }
        }

        @Test
        fun `변화가 없으면 남기지 않는다`() {
            // given — 아직 오지 않았지만 기다릴 사진
            val photoIds = photoFixture.대기중_사진(fixture.galleryId, count = 1)
            age(photoIds, Duration.ofMinutes(2))
            photoStorage.existsAnswer = { false }

            // when
            val sweeps = CapturedLogs(PendingUploadSweeper::class).use { logs ->
                sweeper.sweep()
                logs.eventsOf("upload.sweep")
            }

            // then
            assertThat(sweeps).isEmpty()
        }
    }

    /** 사진을 [ago]만큼 전에 만든 것으로, [lastCheckedAgo]가 있으면 그때 마지막으로 확인한 것으로 옮긴다. */
    private fun age(photoIds: List<Long>, ago: Duration, lastCheckedAgo: Duration? = null) {
        val now = ZonedDateTime.now()
        val createdAt = Timestamp.from(now.minus(ago).toInstant())
        val updatedAt = Timestamp.from(now.minus(lastCheckedAgo ?: ago).toInstant())
        photoIds.forEach { photoId ->
            jdbcTemplate.update("UPDATE photos SET created_at = ?, updated_at = ? WHERE id = ?", createdAt, updatedAt, photoId)
        }
    }

    private fun deletedAtOf(photoId: Long): Timestamp? =
        jdbcTemplate.queryForObject("SELECT deleted_at FROM photos WHERE id = ?", Timestamp::class.java, photoId)
}
