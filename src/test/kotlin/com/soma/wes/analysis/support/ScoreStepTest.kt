package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.ScoreWorkerStateDto
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
import com.soma.wes.support.ManualScoreWorkerPool
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * GPU 제어·폴백 검증. 워커 풀은 [ManualScoreWorkerPool]이 상태를 들고 켜기·끄기를 기록하고, 점수 적재는 픽스처가 흉내 낸다.
 * 시간은 단계에 넣는 시계를 앞으로 돌려 재현한다(인메모리 판단이라 컬럼을 돌릴 것이 없다).
 */
@IntegrationTest
class ScoreStepTest @Autowired constructor(
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val aiTaskSender: FakeAiTaskSender,
    private val eventRecorder: AnalysisJobEventRecorder,
    private val pool: ManualScoreWorkerPool,
) {

    private var galleryId: Long = 0
    private lateinit var clock: MutableClock

    @BeforeEach
    fun setUpBaseData() {
        aiTaskSender.reset()
        pool.reset()
        clock = MutableClock(ZonedDateTime.now())
        pool.now = { ZonedDateTime.now(clock) }
        galleryId = galleryFixture.멤버와_열린_갤러리().galleryId
    }

    private fun step(enabled: Boolean = true) = ScoreStep(
        pool,
        photoPipelineRepository,
        aiTaskSender,
        eventRecorder,
        AnalysisProperties(
            gpu = AnalysisProperties.Gpu(
                enabled = enabled,
                startGrace = Duration.ofMinutes(5),
                idleStopAfter = Duration.ofMinutes(2),
                fallbackAfter = Duration.ofMinutes(10),
                fallbackInterval = Duration.ofMinutes(10),
            ),
        ),
        clock,
    )

    @Nested
    @DisplayName("워커를 켜고 끌 때")
    inner class Workers {

        @Test
        fun `점수 없는 사진이 있고 켜진 워커가 없으면 켠다 — 벡터가 오기 전이라도`() {
            // given
            photoFixture.업로드된_사진(galleryId, count = 1)
            pool.worker("i-1", ScoreWorkerStateDto.STOPPED)
            val step = step()

            // when
            step.advance()
            step.advance()

            // then — 두 번째 걸음은 PENDING 을 보고 다시 켜지 않는다
            assertSoftly { softly ->
                softly.assertThat(pool.starts).hasSize(1)
                softly.assertThat(pool.workers.single().state).isEqualTo(ScoreWorkerStateDto.PENDING)
                softly.assertThat(aiTaskSender.scoreTasks).isEmpty()
            }
        }

        @Test
        fun `일이 없으면 켜지 않는다`() {
            // given — 점수까지 있는 사진뿐
            photoFixture.임베딩된_사진(galleryId, count = 1).forEach { photoFixture.점수_적재(it) }
            pool.worker("i-1", ScoreWorkerStateDto.STOPPED)

            // when
            step().advance()

            // then
            assertThat(pool.starts).isEmpty()
        }

        @Test
        fun `일이 끝났는데 워커가 안 꺼지면 유예와 무진행 시간이 지난 뒤 끈다`() {
            // given — 워커가 방금 점수를 다 냈다
            val photos = photoFixture.임베딩된_사진(galleryId, count = 1)
            pool.worker("i-1", ScoreWorkerStateDto.RUNNING, launchedAt = ZonedDateTime.now(clock).minusMinutes(10))
            val step = step()
            step.advance()
            photos.forEach { photoFixture.점수_적재(it) }
            step.advance()
            assertThat(pool.stops).isEmpty()

            // when — 진행 뒤 2분이 지나도록 워커가 살아 있다
            clock.advance(Duration.ofMinutes(3))
            step.advance()

            // then
            assertThat(pool.stops).containsExactly("i-1")
            assertThat(pool.workers.single().state).isEqualTo(ScoreWorkerStateDto.STOPPING)
        }

        @Test
        fun `켠 지 얼마 안 된 워커는 일이 없어도 끄지 않는다`() {
            // given — 부팅 중(모델 로드)인데 backlog 는 0
            pool.worker("i-1", ScoreWorkerStateDto.RUNNING, launchedAt = ZonedDateTime.now(clock).minusMinutes(1))
            val step = step()

            // when
            step.advance()
            clock.advance(Duration.ofMinutes(3))
            step.advance()

            // then — 유예(5분) 안이다
            assertThat(pool.stops).isEmpty()
        }

        @Test
        fun `풀 호출이 실패해도 걸음은 끝나고 다음 걸음에 다시 본다`() {
            // given
            photoFixture.업로드된_사진(galleryId, count = 1)
            pool.worker("i-1", ScoreWorkerStateDto.STOPPED)
            pool.failNext = true
            val step = step()

            // when
            step.advance()
            step.advance()

            // then
            assertThat(pool.starts).hasSize(1)
        }
    }

    @Nested
    @DisplayName("score Lambda 폴백을 보낼 때")
    inner class Fallback {

        @Test
        fun `GPU 가 꺼져 있으면 벡터만 있는 사진을 바로 보내되 같은 갤러리는 간격 안에 다시 보내지 않는다`() {
            // given
            val photos = photoFixture.임베딩된_사진(galleryId, count = 3)
            photoFixture.업로드된_사진(galleryId, count = 1) // 벡터 없는 사진은 보내지 않는다
            val step = step(enabled = false)

            // when
            step.advance()
            step.advance()
            clock.advance(Duration.ofMinutes(11))
            step.advance()

            // then — 처음과 간격 뒤, 둘
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.scoreTasks).hasSize(2)
                softly.assertThat(aiTaskSender.scoreTasks.first().photoIds).containsExactlyElementsOf(photos)
                softly.assertThat(pool.starts).isEmpty()
            }
        }

        @Test
        fun `한 갤러리에는 상한까지만 보내고 사진이 다 처리된 뒤 새로 올라오면 처음부터 다시 센다`() {
            // given — 점수가 끝내 오지 않는 사진
            val photos = photoFixture.임베딩된_사진(galleryId, count = 2)
            val step = step(enabled = false)

            // when — 간격마다 한 번씩, 상한(3)을 넘겨 다섯 번 기회를 준다
            repeat(5) {
                step.advance()
                clock.advance(Duration.ofMinutes(11))
            }

            // then — 끝없이 보내지 않는다. 남은 것은 잡의 진행 감시가 처리한다
            assertThat(aiTaskSender.scoreTasks).hasSize(3)

            // 그 사진들이 점수를 받고, 나중에 새 사진이 올라오면 다시 보낸다
            photos.forEach { photoFixture.점수_적재(it) }
            step.advance()
            photoFixture.임베딩된_사진(galleryId, count = 1)
            clock.advance(Duration.ofMinutes(11))
            step.advance()
            assertThat(aiTaskSender.scoreTasks).hasSize(4)
        }

        @Test
        fun `GPU 가 켜져 있으면 워커가 오래 아무것도 내지 못할 때만 보낸다`() {
            // given — 워커를 켰지만 점수가 오지 않는다
            val photos = photoFixture.임베딩된_사진(galleryId, count = 2)
            pool.worker("i-1", ScoreWorkerStateDto.STOPPED)
            val step = step()
            step.advance()
            assertThat(pool.starts).hasSize(1)

            // when — 유예 안: 폴백 없음
            clock.advance(Duration.ofMinutes(4))
            step.advance()
            assertThat(aiTaskSender.scoreTasks).isEmpty()

            // 유예 뒤 fallbackAfter(10분)까지 무진행
            clock.advance(Duration.ofMinutes(11))
            step.advance()

            // then
            assertThat(aiTaskSender.scoreTasks.single().photoIds).containsExactlyElementsOf(photos)
        }

        @Test
        fun `워커가 점수를 내고 있으면 보내지 않는다`() {
            // given
            val photos = photoFixture.임베딩된_사진(galleryId, count = 2)
            pool.worker("i-1", ScoreWorkerStateDto.RUNNING, launchedAt = ZonedDateTime.now(clock).minusMinutes(30))
            val step = step()
            step.advance()

            // when — 8분마다 한 장씩 진행
            clock.advance(Duration.ofMinutes(8))
            photoFixture.점수_적재(photos[0])
            step.advance()
            clock.advance(Duration.ofMinutes(8))
            step.advance()

            // then — 마지막 진행 뒤 10분이 안 됐다
            assertThat(aiTaskSender.scoreTasks).isEmpty()
        }

        @Test
        fun `켜진 워커가 없고 켤 수도 없으면 fallbackAfter 뒤에 보낸다`() {
            // given — 풀에 인스턴스가 없다
            val photos = photoFixture.임베딩된_사진(galleryId, count = 1)
            val step = step()

            // when
            step.advance()
            clock.advance(Duration.ofMinutes(11))
            step.advance()

            // then
            assertThat(aiTaskSender.scoreTasks.single().photoIds).containsExactlyElementsOf(photos)
        }
    }

    /** 걸음마다 앞으로 돌릴 수 있는 시계. */
    private class MutableClock(start: ZonedDateTime) : Clock() {
        private var instant: Instant = start.toInstant()
        private val zone: ZoneId = start.zone

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }

        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }
}
