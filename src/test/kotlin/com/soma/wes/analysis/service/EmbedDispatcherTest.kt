package com.soma.wes.analysis.service

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 임베더 배정 검증. 임베더가 DB에 쓰는 일(벡터·되돌림)은 픽스처와 jdbc로 흉내 낸다. 배치·상한은 작은 값의 설정으로 직접 만든 디스패처가 본다. */
@IntegrationTest
class EmbedDispatcherTest @Autowired constructor(
    private val embedDispatcher: EmbedDispatcher,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val aiTaskSender: FakeAiTaskSender,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {

    private var galleryId: Long = 0

    @BeforeEach
    fun setUpBaseData() {
        aiTaskSender.reset()
        galleryId = galleryFixture.멤버와_열린_갤러리().galleryId
    }

    private fun dispatcher(
        batchSize: Int = 50,
        maxInFlight: Int = 32,
        maxAttempts: Int = 3,
        redispatchAfter: Duration = Duration.ofMinutes(10),
    ) = EmbedDispatcher(
        photoPipelineRepository,
        aiTaskSender,
        AnalysisProperties(
            embedBatchSize = batchSize,
            embedMaxInFlight = maxInFlight,
            embedMaxAttempts = maxAttempts,
            embedRedispatchAfter = redispatchAfter,
        ),
        clock,
    )

    private fun embedByLambda(photoIds: Collection<Long>) {
        photoIds.forEach { photoFixture.벡터_적재(it, FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { v -> v[0] = 1f }) }
    }

    private fun dispatchedLongAgo(photoIds: Collection<Long>) {
        jdbcTemplate.update(
            "UPDATE photos SET dispatched_at = now() - interval '11 minutes' WHERE id IN (${photoIds.joinToString(",")})",
        )
    }

    @Nested
    @DisplayName("배정할 때")
    inner class Dispatch {

        @Test
        fun `갤러리마다 한 배치씩 보내고 배정된 사진은 다시 보내지 않는다`() {
            // given — 두 갤러리
            val first = photoFixture.업로드된_사진(galleryId, count = 3)
            val other = galleryFixture.멤버와_열린_갤러리().galleryId
            val second = photoFixture.업로드된_사진(other, count = 2)
            photoFixture.대기중_사진(galleryId, count = 1)

            // when
            val sent = embedDispatcher.dispatch()
            val sentAgain = embedDispatcher.dispatch()

            // then — PENDING 은 대상이 아니고, 갤러리마다 EVENT 하나
            assertSoftly { softly ->
                softly.assertThat(sent).isEqualTo(2)
                softly.assertThat(sentAgain).isZero()
                softly.assertThat(aiTaskSender.embedTasks.map { it.galleryId }).containsExactly(galleryId, other)
                softly.assertThat(aiTaskSender.embedTasks[0].photoIds).containsExactlyElementsOf(first)
                softly.assertThat(aiTaskSender.embedTasks[1].photoIds).containsExactlyElementsOf(second)
                softly.assertThat(photoPipelineRepository.countInFlightEmbedBatches()).isEqualTo(2)
            }
        }

        @Test
        fun `배치 크기와 전역 in-flight 상한을 지키고 벡터가 오면 자리가 난다`() {
            // given
            val photos = photoFixture.업로드된_사진(galleryId, count = 5)
            val dispatcher = dispatcher(batchSize = 2, maxInFlight = 2)

            // when — 자리 둘: 2장 + 2장, 한 장은 남는다
            dispatcher.dispatch()
            assertThat(aiTaskSender.embedTasks.map { it.photoIds }).containsExactly(photos.take(2), photos.drop(2).take(2))
            dispatcher.dispatch()
            assertThat(aiTaskSender.embedTasks).hasSize(2)

            // 임베더 역할: 첫 배치가 끝나면 자리가 하나 난다
            embedByLambda(photos.take(2))
            dispatcher.dispatch()

            // then
            assertThat(aiTaskSender.embedTasks).hasSize(3)
            assertThat(aiTaskSender.embedTasks.last().photoIds).containsExactly(photos.last())
            assertThat(aiTaskSender.embeddedPhotoIds).containsExactlyElementsOf(photos)
        }

        @Test
        fun `이미 벡터가 있거나 실패한 사진은 보내지 않는다`() {
            // given
            val embedded = photoFixture.임베딩된_사진(galleryId, count = 1)
            val failed = photoFixture.업로드된_사진(galleryId, count = 1).also { photoFixture.분석_실패(it.single()) }
            val fresh = photoFixture.업로드된_사진(galleryId, count = 1)

            // when
            embedDispatcher.dispatch()

            // then
            assertThat(aiTaskSender.embeddedPhotoIds).containsExactlyElementsOf(fresh).doesNotContainAnyElementsOf(embedded + failed)
        }

        @Test
        fun `두 스윕이 동시에 집어도 사진은 한 번씩만 보낸다`() {
            // given
            val photos = photoFixture.업로드된_사진(galleryId, count = 10)
            val dispatcher = dispatcher(batchSize = 3)

            // when
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val runs = (1..2).map { pool.submit { barrier.await(5, TimeUnit.SECONDS); dispatcher.dispatch() } }
                runs.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            // then
            assertThat(aiTaskSender.embeddedPhotoIds).containsExactlyInAnyOrderElementsOf(photos).doesNotHaveDuplicates()
        }
    }

    @Nested
    @DisplayName("배정이 끝나지 않을 때")
    inner class Stale {

        @Test
        fun `오래된 배정은 되돌려 다시 보내고 상한에 닿으면 실패로 표시한다`() {
            // given
            val photo = photoFixture.업로드된_사진(galleryId, count = 1).single()
            val dispatcher = dispatcher(maxAttempts = 2)

            // when — 첫 배정 → 오래됨 → 두 번째 배정 → 오래됨 → 상한
            dispatcher.dispatch()
            dispatchedLongAgo(listOf(photo))
            dispatcher.dispatch()
            assertThat(aiTaskSender.embedTasks).hasSize(2)
            dispatchedLongAgo(listOf(photo))
            dispatcher.dispatch()

            // then — 더 보내지 않고 사진 단위 실패로 남아 기대 장수에서 빠진다
            val progress = photoPipelineRepository.progressOf(galleryId, liveSince = ZonedDateTime.now(clock))
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.embedTasks).hasSize(2)
                softly.assertThat(photoAnalysisRepository.findById(photo).orElseThrow().error).isEqualTo(PhotoPipelineRepository.EMBED_ATTEMPTS_EXCEEDED)
                softly.assertThat(progress.failed).isEqualTo(1)
                softly.assertThat(progress.expected).isZero()
            }
        }

        @Test
        fun `오래됐어도 벡터가 온 사진은 되돌리지 않는다`() {
            // given
            val photos = photoFixture.업로드된_사진(galleryId, count = 2)
            embedDispatcher.dispatch()
            embedByLambda(listOf(photos[0]))
            dispatchedLongAgo(photos)

            // when
            embedDispatcher.dispatch()

            // then — 벡터 없는 한 장만 다시 나간다
            assertThat(aiTaskSender.embedTasks).hasSize(2)
            assertThat(aiTaskSender.embedTasks.last().photoIds).containsExactly(photos[1])
        }

        @Test
        fun `호출이 실패하면 배정과 시도 수를 되돌려 다음 걸음이 다시 집는다`() {
            // given
            val photo = photoFixture.업로드된_사진(galleryId, count = 1).single()
            aiTaskSender.failNext = true

            // when
            val sent = embedDispatcher.dispatch()
            val row = jdbcTemplate.queryForMap("SELECT dispatched_at, embed_attempts FROM photos WHERE id = ?", photo)
            val sentAgain = embedDispatcher.dispatch()

            // then
            assertSoftly { softly ->
                softly.assertThat(sent).isZero()
                softly.assertThat(row["dispatched_at"]).isNull()
                softly.assertThat(row["embed_attempts"]).isEqualTo(0)
                softly.assertThat(sentAgain).isEqualTo(1)
                softly.assertThat(aiTaskSender.embeddedPhotoIds).containsExactly(photo)
            }
        }

        @Test
        fun `리셋은 분석 행을 지우고 배정 추적을 초기화한다`() {
            // given
            val photos = photoFixture.임베딩된_사진(galleryId, count = 2)
            jdbcTemplate.update("UPDATE photos SET dispatched_at = now(), embed_attempts = 3 WHERE gallery_id = ?", galleryId)

            // when
            val deleted = photoPipelineRepository.resetAnalysis(galleryId)
            embedDispatcher.dispatch()

            // then — 전부 다시 배정 대상이다
            assertSoftly { softly ->
                softly.assertThat(deleted).isEqualTo(2)
                softly.assertThat(photoAnalysisRepository.findAllById(photos)).isEmpty()
                softly.assertThat(aiTaskSender.embeddedPhotoIds).containsExactlyElementsOf(photos)
            }
        }
    }
}
