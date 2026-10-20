package com.soma.wes.analysis.service

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisFailureCode
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisJobEventType
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.AiFailureDto
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.repository.AnalysisJobEventRepository
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.support.AnalysisJobEventListener
import com.soma.wes.analysis.support.EmbedStep
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 실패 큐로 온 Lambda 실패에 대한 반응(#286 (c) ②) — 다시 보내지 않고 재전송 대기를 앞당기면, 다음 걸음의 단계가 다시 보낸다.
 * 실패 시각 뒤에 이미 다시 보낸 작업·끝난 작업은 건드리지 않는다.
 */
@IntegrationTest
class AiFailureServiceTest @Autowired constructor(
    private val aiFailureService: AiFailureService,
    private val pipeline: AnalysisPipelineService,
    private val embedStep: EmbedStep,
    private val analysisService: AnalysisService,
    private val analysisJobRepository: AnalysisJobRepository,
    private val eventRepository: AnalysisJobEventRepository,
    private val eventListener: AnalysisJobEventListener,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val aiTaskSender: FakeAiTaskSender,
    private val properties: AnalysisProperties,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {

    private lateinit var gallery: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        aiTaskSender.reset()
        gallery = galleryFixture.멤버와_열린_갤러리()
    }

    private fun failure(task: AiTaskDto?, failedAt: ZonedDateTime = ZonedDateTime.now(clock)) = AiFailureDto(
        task = task,
        functionName = "wes-dev-test",
        condition = "RetriesExhausted",
        errorType = "RuntimeError",
        errorMessage = "Runtime exited with error: signal: killed",
        failedAt = failedAt,
    )

    /** 이력은 커밋 뒤 이력 스레드가 쓴다 — 넘긴 것이 다 쓰인 뒤에 읽는다. */
    private fun eventTypes(jobId: Long): List<AnalysisJobEventType> {
        eventListener.awaitIdle()
        return eventRepository.findAllByJobIdOrderByIdAsc(jobId).map { it.type }
    }

    private fun job(jobId: Long): AnalysisJob = analysisJobRepository.findById(jobId).orElseThrow()

    @Nested
    @DisplayName("임베더가 실패하면")
    inner class Embed {

        @Test
        fun `그 배치의 배정을 풀어 다음 걸음이 곧바로 다시 보내고 잡 이력에 남긴다`() {
            // given — 배정되어 임베더로 나간 배치
            val photos = photoFixture.업로드된_사진(gallery.galleryId, count = 2)
            val jobId = analysisService.request(gallery.galleryId, gallery.photographer.requiredId).jobId
            embedStep.advance()
            val sent = aiTaskSender.embedTasks.single()

            // when
            aiFailureService.handle(failure(sent))
            embedStep.advance()

            // then — 재배정 시간(분 단위)을 기다리지 않고 같은 사진이 다시 나간다
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.embedTasks).hasSize(2)
                softly.assertThat(aiTaskSender.embedTasks.last().photoIds).containsExactlyElementsOf(photos)
                softly.assertThat(eventTypes(jobId)).contains(AnalysisJobEventType.EMBED_FAILED)
            }
        }

        @Test
        fun `두 번째 시도의 실패는 1분을 두고 다시 보낸다`() {
            // given
            val photos = photoFixture.업로드된_사진(gallery.galleryId, count = 1)
            embedStep.advance()
            jdbcTemplate.update("UPDATE photos SET embed_attempts = 2 WHERE id = ?", photos.single())
            val before = ZonedDateTime.now(clock)

            // when
            aiFailureService.handle(failure(aiTaskSender.embedTasks.single()))
            embedStep.advance()

            // then — 아직 다시 나가지 않고, 배정 시각이 "1분 뒤 풀림" 자리로 옮겨졌다
            val dispatchedAt = jdbcTemplate.queryForObject("SELECT dispatched_at FROM photos WHERE id = ?", OffsetDateTime::class.java, photos.single())
            val releaseAt = dispatchedAt!!.toZonedDateTime().plus(properties.embedRedispatchAfter)
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.embedTasks).hasSize(1)
                softly.assertThat(releaseAt).isAfterOrEqualTo(before.plus(Duration.ofSeconds(59)))
                softly.assertThat(releaseAt).isBeforeOrEqualTo(ZonedDateTime.now(clock).plus(Duration.ofSeconds(61)))
            }
        }

        @Test
        fun `실패 뒤에 다시 배정된 사진은 건드리지 않는다`() {
            // given — 실패 시각이 지금 배정보다 앞선다(그 사이 다시 배정됐다)
            photoFixture.업로드된_사진(gallery.galleryId, count = 1)
            embedStep.advance()

            // when
            aiFailureService.handle(failure(aiTaskSender.embedTasks.single(), failedAt = ZonedDateTime.now(clock).minusHours(1)))
            embedStep.advance()

            // then
            assertThat(aiTaskSender.embedTasks).hasSize(1)
        }
    }

    @Nested
    @DisplayName("categorize 가 실패하면")
    inner class Categorize {

        private fun categorizingJob(): Long {
            photoFixture.임베딩된_사진(gallery.galleryId, count = 2).forEach { photoFixture.점수_적재(it) }
            val jobId = analysisService.request(gallery.galleryId, gallery.photographer.requiredId).jobId
            pipeline.advance()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
            return jobId
        }

        @Test
        fun `20분을 기다리지 않고 다음 걸음이 다시 보내고 잡 이력에 남긴다`() {
            // given
            val jobId = categorizingJob()

            // when
            aiFailureService.handle(failure(AiTaskDto.Categorize(galleryId = gallery.galleryId, jobId = jobId)))
            pipeline.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.categorizeTasks).hasSize(2)
                softly.assertThat(job(jobId).attempts).isEqualTo(2)
                softly.assertThat(eventTypes(jobId))
                    .containsSubsequence(AnalysisJobEventType.CATEGORIZE_FAILED, AnalysisJobEventType.CATEGORIZE_RESENT)
            }
        }

        @Test
        fun `상한에 닿은 실패는 기다리지 않고 곧바로 실패로 닫힌다`() {
            // given
            val jobId = categorizingJob()
            jdbcTemplate.update("UPDATE analysis_jobs SET attempts = ? WHERE id = ?", properties.categorizeMaxAttempts, jobId)

            // when
            aiFailureService.handle(failure(AiTaskDto.Categorize(galleryId = gallery.galleryId, jobId = jobId)))
            pipeline.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(job(jobId).status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job(jobId).errorCode).isEqualTo(AnalysisFailureCode.CATEGORIZE_TIMEOUT)
                softly.assertThat(aiTaskSender.categorizeTasks).hasSize(1)
            }
        }

        @Test
        fun `같은 실패를 두 번 받아도 한 번만 다시 보낸다`() {
            // given
            val jobId = categorizingJob()
            val message = failure(AiTaskDto.Categorize(galleryId = gallery.galleryId, jobId = jobId))

            // when — 다시 보낸 뒤 같은 메시지가 또 온다
            aiFailureService.handle(message)
            pipeline.advance()
            aiFailureService.handle(message)
            pipeline.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.categorizeTasks).hasSize(2)
                softly.assertThat(job(jobId).attempts).isEqualTo(2)
            }
        }

        @Test
        fun `CATEGORIZING 이 아닌 잡이면 건드리지 않는다`() {
            // given — 아직 점수를 기다리는 잡
            photoFixture.업로드된_사진(gallery.galleryId, count = 1)
            val jobId = analysisService.request(gallery.galleryId, gallery.photographer.requiredId).jobId

            // when
            aiFailureService.handle(failure(AiTaskDto.Categorize(galleryId = gallery.galleryId, jobId = jobId)))

            // then
            assertSoftly { softly ->
                softly.assertThat(job(jobId).status).isEqualTo(AnalysisStatus.ANALYZING)
                softly.assertThat(eventTypes(jobId)).doesNotContain(AnalysisJobEventType.CATEGORIZE_FAILED)
            }
        }
    }

    @Test
    fun `모르는 작업과 보낸 적 없는 score 폴백의 실패는 기록만 하고 넘어간다`() {
        assertThatCode {
            aiFailureService.handle(failure(null))
            aiFailureService.handle(failure(AiTaskDto.Score(galleryId = gallery.galleryId, photoIds = listOf(1))))
            aiFailureService.handle(failure(AiTaskDto.Rank(galleryId = gallery.galleryId)))
        }.doesNotThrowAnyException()
    }
}
