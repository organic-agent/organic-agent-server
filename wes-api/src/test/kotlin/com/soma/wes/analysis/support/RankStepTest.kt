package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.AnalysisPipelineService
import com.soma.wes.analysis.service.AnalysisService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
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
 * 폴더 뒤에 순위(백분위·연사 대표)를 채우는 단계(#274 2물결). 폴더까지는 파이프라인을 돌려 DONE 잡을 만들고, 화질 점수(score 2단계)는
 * 픽스처가 흉내 낸다. 기본 컨텍스트는 이 단계가 꺼져 있어 켠 설정으로 단계를 직접 만든다. 시간은 단계에 넣는 시계로 앞당긴다.
 */
@IntegrationTest
class RankStepTest @Autowired constructor(
    private val pipeline: AnalysisPipelineService,
    private val analysisService: AnalysisService,
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val eventRecorder: AnalysisJobEventRecorder,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val aiTaskSender: FakeAiTaskSender,
) {

    private lateinit var fixture: OpenGallery
    private lateinit var clock: MutableClock

    @BeforeEach
    fun setUpBaseData() {
        aiTaskSender.reset()
        fixture = galleryFixture.멤버와_열린_갤러리()
        clock = MutableClock(ZonedDateTime.now())
    }

    private fun step(enabled: Boolean = true) = RankStep(
        analysisJobRepository,
        photoPipelineRepository,
        aiTaskSender,
        eventRecorder,
        AnalysisProperties(
            qualityStage = AnalysisProperties.QualityStage(enabled = enabled, rankTimeout = Duration.ofMinutes(10), rankMaxAttempts = 2),
        ),
        clock,
    )

    @Nested
    @DisplayName("rank 모드를 보낼 때")
    inner class Send {

        @Test
        fun `폴더를 만든 갤러리에 화질 점수가 다 차면 rank 모드를 한 번 보낸다`() {
            // given
            val (jobId, photos) = folderedJob(photoCount = 2)
            photos.forEach { photoFixture.화질점수_적재(it) }
            val step = step()

            // when — 두 번째 걸음은 기다릴 시간 안이라 다시 보내지 않는다
            step.advance()
            step.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.rankTasks).containsExactly(AiTaskDto.Rank(galleryId = fixture.galleryId))
                softly.assertThat(aiTaskSender.rankTasks.single().mode).isEqualTo(AiTaskDto.Rank.RANK_MODE)
                softly.assertThat(job(jobId).rankAttempts).isEqualTo(1)
                softly.assertThat(job(jobId).rankDispatchedAt).isNotNull()
                softly.assertThat(job(jobId).status).isEqualTo(AnalysisStatus.DONE)
            }
        }

        @Test
        fun `화질 점수가 한 장이라도 남았으면 기다린다`() {
            // given — 백분위는 갤러리 안의 상대 순위라 덜 찬 채로 매기지 않는다
            val (_, photos) = folderedJob(photoCount = 2)
            photoFixture.화질점수_적재(photos[0])

            // when
            step().advance()

            // then
            assertThat(aiTaskSender.rankTasks).isEmpty()
        }

        @Test
        fun `순위까지 이미 찬 갤러리에는 보내지 않는다`() {
            // given
            val (_, photos) = folderedJob(photoCount = 1)
            photos.forEach {
                photoFixture.화질점수_적재(it)
                photoFixture.백분위_적재(it)
            }

            // when
            step().advance()

            // then
            assertThat(aiTaskSender.rankTasks).isEmpty()
        }

        @Test
        fun `살아 있는 잡이 있으면 그 잡의 categorize 에 맡기고 보내지 않는다`() {
            // given — 다음 업로드의 잡이 돌고 있다
            val (_, photos) = folderedJob(photoCount = 1)
            photos.forEach { photoFixture.화질점수_적재(it) }
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            analysisService.request(fixture.galleryId, fixture.photographer.requiredId)

            // when
            step().advance()

            // then
            assertThat(aiTaskSender.rankTasks).isEmpty()
        }

        @Test
        fun `실행기가 없으면 시도 횟수를 쓰지 않고 기다린다`() {
            // given — 실행기를 고친 뒤에도 상한에 막히지 않게
            val (jobId, photos) = folderedJob(photoCount = 1)
            photos.forEach { photoFixture.화질점수_적재(it) }
            aiTaskSender.available = false

            // when
            step().advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.rankTasks).isEmpty()
                softly.assertThat(job(jobId).rankAttempts).isZero()
            }
        }

        @Test
        fun `꺼져 있으면 보내지 않는다`() {
            // given
            val (_, photos) = folderedJob(photoCount = 1)
            photos.forEach { photoFixture.화질점수_적재(it) }

            // when
            step(enabled = false).advance()

            // then
            assertThat(aiTaskSender.rankTasks).isEmpty()
        }
    }

    @Nested
    @DisplayName("결과가 늦을 때")
    inner class Late {

        @Test
        fun `기다릴 시간이 지나면 다시 보내고 상한에 닿으면 그만 보낸다`() {
            // given
            val (jobId, photos) = folderedJob(photoCount = 1)
            photos.forEach { photoFixture.화질점수_적재(it) }
            val step = step()

            // when
            step.advance()
            clock.advance(Duration.ofMinutes(11))
            step.advance()
            clock.advance(Duration.ofMinutes(11))
            step.advance()

            // then — 상한 2
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.rankTasks).hasSize(2)
                softly.assertThat(job(jobId).rankAttempts).isEqualTo(2)
            }
        }

        @Test
        fun `호출이 실패하면 전송 시각을 지워 다음 걸음이 곧바로 다시 보낸다`() {
            // given
            val (jobId, photos) = folderedJob(photoCount = 1)
            photos.forEach { photoFixture.화질점수_적재(it) }
            val step = step()
            aiTaskSender.failNext = true

            // when
            step.advance()
            val afterFailure = job(jobId)
            step.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(afterFailure.rankDispatchedAt).isNull()
                softly.assertThat(afterFailure.rankAttempts).isEqualTo(1)
                softly.assertThat(aiTaskSender.rankTasks).hasSize(1)
                softly.assertThat(job(jobId).rankAttempts).isEqualTo(2)
            }
        }
    }

    /** 1단계 점수만 있는 사진으로 잡을 돌려 categorize 가 그룹만 쓴 채 폴더를 만들고 DONE 으로 닫힌 상태. */
    private fun folderedJob(photoCount: Int): Pair<Long, List<Long>> {
        val photos = photoFixture.임베딩된_사진(fixture.galleryId, photoCount).onEach { photoFixture.점수_적재(it) }
        val jobId = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId
        pipeline.advance()
        photos.forEach { photoFixture.그룹만_적재(it, embedGroupId = 1) }
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
        pipeline.advance()
        assertThat(job(jobId).status).isEqualTo(AnalysisStatus.DONE)
        return jobId to photos
    }

    private fun job(jobId: Long): AnalysisJob = analysisJobRepository.findById(jobId).orElseThrow()

    private class MutableClock(private var now: ZonedDateTime) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun getZone(): ZoneId = now.zone

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now.toInstant()
    }
}
