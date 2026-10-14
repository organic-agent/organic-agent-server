package com.soma.wes.analysis.support

import com.soma.wes.analysis.domain.AnalysisJobEventType
import com.soma.wes.analysis.repository.AnalysisJobEventRepository
import com.soma.wes.analysis.service.AnalysisPipelineService
import com.soma.wes.analysis.service.AnalysisService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * 분석 잡의 이력 — 잡이 단계를 넘을 때마다 `analysis_job_events` 에 한 줄씩 남는다. 로그(`job.*`)와 같은 지점이고,
 * 로그가 사라진 뒤에도 잡이 어떻게 흘렀는지 읽을 수 있어야 한다.
 */
@IntegrationTest
class AnalysisJobEventRecorderTest @Autowired constructor(
    private val pipeline: AnalysisPipelineService,
    private val analysisService: AnalysisService,
    private val eventRecorder: AnalysisJobEventRecorder,
    private val eventRepository: AnalysisJobEventRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val aiTaskSender: FakeAiTaskSender,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) {

    companion object {
        /** 없는 잡 번호 — 이 번호로 이력을 남기면 FK 위반으로 실패한다. */
        private const val MISSING_JOB_ID = -1L
    }

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        aiTaskSender.reset()
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    /** 점수까지 찬 사진 3장과 그 잡. */
    private fun scoredJob(conceptCount: Int? = null): Pair<Long, List<Long>> {
        val photos = photoFixture.임베딩된_사진(fixture.galleryId, count = 3).onEach { photoFixture.점수_적재(it) }
        val jobId = analysisService.request(fixture.galleryId, fixture.photographer.requiredId, conceptCount).jobId
        return jobId to photos
    }

    private fun typesOf(jobId: Long) = eventRepository.findAllByJobIdOrderByIdAsc(jobId).map { it.type }

    @Nested
    @DisplayName("잡이 끝까지 갈 때")
    inner class HappyPath {

        @Test
        fun `생성·전송·물질화·완료가 일어난 순서로 남는다`() {
            // given
            val (jobId, photos) = scoredJob(conceptCount = 4)

            // when — categorize 를 보내고, 결과가 온 뒤 폴더를 만들고 닫는다
            pipeline.advance()
            photos.forEach { photoFixture.백분위_적재(it, embedGroupId = 1) }
            recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
            pipeline.advance()

            // then
            val events = eventRepository.findAllByJobIdOrderByIdAsc(jobId)
            val detailByType = events.associate { it.type to it.detail.orEmpty() }
            assertSoftly { softly ->
                softly.assertThat(events.map { it.type }).containsExactly(
                    AnalysisJobEventType.CREATED,
                    AnalysisJobEventType.CATEGORIZE_SENT,
                    AnalysisJobEventType.FOLDER_MATERIALIZED,
                    AnalysisJobEventType.DONE,
                )
                softly.assertThat(events.map { it.galleryId }.distinct()).containsExactly(fixture.galleryId)
                softly.assertThat(detailByType.getValue(AnalysisJobEventType.CREATED)).containsEntry("conceptCount", 4)
                softly.assertThat(detailByType.getValue(AnalysisJobEventType.CATEGORIZE_SENT)).containsEntry("scored", 3)
                softly.assertThat(detailByType.getValue(AnalysisJobEventType.FOLDER_MATERIALIZED))
                    .containsEntry("newConcepts", 1).containsEntry("merged", 0)
                softly.assertThat(detailByType.getValue(AnalysisJobEventType.DONE))
                    .containsEntry("folders", 1).containsEntry("assigned", 3)
            }
        }

        @Test
        fun `값이 없는 부가 정보는 싣지 않는다`() {
            // when — 컨셉 수를 정하지 않은 잡
            val (jobId, _) = scoredJob(conceptCount = null)

            // then
            val created = eventRepository.findAllByJobIdOrderByIdAsc(jobId).single()
            assertThat(created.detail.orEmpty()).containsKey("expected").doesNotContainKey("conceptCount")
        }
    }

    @Nested
    @DisplayName("잡이 순탄하지 않을 때")
    inner class Trouble {

        @Test
        fun `categorize 전송이 실패하면 전송 실패가 남고 다음 회차의 재전송이 이어서 남는다`() {
            // given
            val (jobId, _) = scoredJob()
            aiTaskSender.failNext = true

            // when — 첫 회차는 보내기에 실패하고, 다음 회차가 다시 보낸다
            pipeline.advance()
            pipeline.advance()

            // then
            assertThat(typesOf(jobId)).containsExactly(
                AnalysisJobEventType.CREATED,
                AnalysisJobEventType.CATEGORIZE_SENT,
                AnalysisJobEventType.CATEGORIZE_SEND_FAILED,
                AnalysisJobEventType.CATEGORIZE_RESENT,
            )
        }

        @Test
        fun `categorize 가 오류를 남기면 실패 이유가 남는다`() {
            // given — Lambda 가 error 컬럼을 썼다
            val (jobId, _) = scoredJob()
            pipeline.advance()
            jdbcTemplate.update("UPDATE analysis_jobs SET error = 'bedrock timeout', updated_at = now() WHERE id = ?", jobId)

            // when
            pipeline.advance()

            // then
            val failed = eventRepository.findAllByJobIdOrderByIdAsc(jobId).last()
            assertSoftly { softly ->
                softly.assertThat(failed.type).isEqualTo(AnalysisJobEventType.FAILED)
                softly.assertThat(failed.detail.orEmpty())
                    .containsEntry("from", "CATEGORIZING")
                    .containsEntry("errorCode", "CATEGORIZE_FAILED")
                    .containsEntry("error", "bedrock timeout")
            }
        }
    }

    @Nested
    @DisplayName("갤러리 단위로 남길 때")
    inner class ForActiveJob {

        @Test
        fun `진행 중인 잡이 있으면 그 잡의 이력으로 남는다`() {
            // given
            val (jobId, _) = scoredJob()

            // when
            eventRecorder.recordForActiveJob(fixture.galleryId, AnalysisJobEventType.SCORE_FALLBACK, mapOf("photos" to 3))

            // then
            assertThat(typesOf(jobId)).containsExactly(AnalysisJobEventType.CREATED, AnalysisJobEventType.SCORE_FALLBACK)
        }

        @Test
        fun `진행 중인 잡이 없으면 남기지 않는다`() {
            // when — 잡 없이 사진만 올라온 갤러리
            eventRecorder.recordForActiveJob(fixture.galleryId, AnalysisJobEventType.SCORE_FALLBACK, mapOf("photos" to 3))

            // then
            assertThat(eventRepository.findAll().filter { it.galleryId == fixture.galleryId }).isEmpty()
        }
    }

    /**
     * 이력은 호출자의 트랜잭션에 실리지 않는다 — 기록이 실패해도 잡 생성·닫기·물질화가 되돌아가지 않고,
     * 호출자가 되돌아가면 이력도 남지 않는다.
     */
    @Nested
    @DisplayName("호출자가 트랜잭션 안일 때")
    inner class InsideTransaction {

        @Test
        fun `기록이 실패해도 호출자의 트랜잭션은 커밋된다`() {
            // given
            val (jobId, _) = scoredJob()

            // when — 같은 트랜잭션에서 잡을 고치고, 없는 잡 번호로 이력을 남긴다(FK 위반)
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.update("UPDATE analysis_jobs SET attempts = 7 WHERE id = ?", jobId)
                eventRecorder.record(MISSING_JOB_ID, fixture.galleryId, AnalysisJobEventType.DONE)
            }

            // then — 예외가 올라오지 않고 잡의 변경은 남는다
            val attempts = jdbcTemplate.queryForObject("SELECT attempts FROM analysis_jobs WHERE id = ?", Int::class.java, jobId)
            assertSoftly { softly ->
                softly.assertThat(attempts).isEqualTo(7)
                softly.assertThat(eventRepository.findAllByJobIdOrderByIdAsc(MISSING_JOB_ID)).isEmpty()
            }
        }

        @Test
        fun `호출자가 되돌아가면 이력도 남지 않는다`() {
            // given
            val (jobId, _) = scoredJob()

            // when
            transactionTemplate.executeWithoutResult { status ->
                eventRecorder.record(jobId, fixture.galleryId, AnalysisJobEventType.DONE)
                status.setRollbackOnly()
            }

            // then
            assertThat(typesOf(jobId)).containsExactly(AnalysisJobEventType.CREATED)
        }

        @Test
        fun `커밋되면 부른 순서대로 남는다`() {
            // given
            val (jobId, _) = scoredJob()

            // when
            transactionTemplate.executeWithoutResult {
                eventRecorder.record(jobId, fixture.galleryId, AnalysisJobEventType.FOLDER_MATERIALIZED)
                eventRecorder.record(jobId, fixture.galleryId, AnalysisJobEventType.DONE)
                // 커밋 전에는 아직 쓰이지 않았다
                assertThat(typesOf(jobId)).containsExactly(AnalysisJobEventType.CREATED)
            }

            // then
            assertThat(typesOf(jobId)).containsExactly(
                AnalysisJobEventType.CREATED,
                AnalysisJobEventType.FOLDER_MATERIALIZED,
                AnalysisJobEventType.DONE,
            )
        }
    }

    @Test
    fun `트랜잭션 밖에서 기록이 실패해도 예외가 올라오지 않는다`() {
        // when
        eventRecorder.record(MISSING_JOB_ID, fixture.galleryId, AnalysisJobEventType.DONE)

        // then
        assertThat(eventRepository.findAllByJobIdOrderByIdAsc(MISSING_JOB_ID)).isEmpty()
    }

    @Test
    fun `잡이 지워지면 이력도 같이 지워진다`() {
        // given
        val (jobId, _) = scoredJob()
        pipeline.advance()

        // when
        jdbcTemplate.update("DELETE FROM analysis_jobs WHERE id = ?", jobId)

        // then
        assertThat(eventRepository.findAllByJobIdOrderByIdAsc(jobId)).isEmpty()
    }
}
