package com.soma.wes.analysis.service

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisMode
import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.FakeStageInvoker
import com.soma.wes.support.IntegrationTest
import java.time.Clock
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
 * 상태 기계 검증. Lambda는 [FakeStageInvoker]가 호출을 기록만 하고, Lambda가 DB에 쓰는 일(claim·DONE·status)은
 * jdbc로 직접 흉내 낸다. 시간은 컬럼을 과거로 돌려 재현한다.
 */
@IntegrationTest
class AnalysisOrchestratorTest @Autowired constructor(
    private val orchestrator: AnalysisOrchestrator,
    private val analysisService: AnalysisService,
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoRepository: PhotoRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val stageInvoker: FakeStageInvoker,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
    private val jdbcTemplate: JdbcTemplate,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        stageInvoker.reset()
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    private fun requestFull(force: Boolean = false): Long =
        analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL, force).jobId

    private fun job(jobId: Long): AnalysisJob = analysisJobRepository.findById(jobId).orElseThrow()

    private fun stages(jobId: Long): List<AnalysisStage> = stageInvoker.callsOf(jobId).map { it.stage }

    @Nested
    @DisplayName("EMBED 단계를 관측으로 열고 닫을 때")
    inner class Embed {

        @Test
        fun `요청은 EMBED를 한 번 보내고 벡터가 다 차면 다음 단계로 넘어간다`() {
            // given — 업로드만 된 사진 둘
            val photos = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val jobId = requestFull()
            assertThat(job(jobId).stageStatus).isEqualTo(AnalysisStatus.RUNNING)

            // when — 아직 한 장도 안 됐다: 기다린다
            orchestrator.sweep()
            assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED)

            // 임베더가 한 장을 적재하면 진행으로 친다
            photoFixture.벡터_적재(photos[0], FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { it[0] = 1f })
            orchestrator.sweep()
            assertThat(job(jobId).observedProgress).isEqualTo(1)
            assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED)

            // 전부 적재되면 EMBED가 닫히고 SCORE가 나간다
            photoFixture.벡터_적재(photos[1], FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { it[1] = 1f })
            orchestrator.sweep()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED, AnalysisStage.SCORE)
                softly.assertThat(job.stage).isEqualTo(AnalysisStage.SCORE)
                softly.assertThat(job.stageStatus).isEqualTo(AnalysisStatus.PENDING)
                softly.assertThat(job.stageAttempts).isEqualTo(1)
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.PENDING)
            }
        }

        @Test
        fun `이미 임베딩된 갤러리의 EMBED 모드 잡은 다음 스윕에서 DONE이 된다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 2)
            val jobId = analysisService.requestEmbedding(fixture.galleryId, fixture.photographer.id!!, force = false).jobId

            // when
            orchestrator.sweep()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.DONE)
                softly.assertThat(job.stage).isEqualTo(AnalysisStage.EMBED)
                softly.assertThat(job.finishedAt).isNotNull()
                softly.assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED)
            }
        }

        @Test
        fun `force는 보낸 뒤 갱신된 벡터만 진행으로 센다`() {
            // given — 벡터가 이미 있어도 force면 다시 써야 끝이다
            val photos = photoFixture.임베딩된_사진(fixture.galleryId, count = 2)
            val jobId = analysisService.requestEmbedding(fixture.galleryId, fixture.photographer.id!!, force = true).jobId
            jdbcTemplate.update("UPDATE photo_analysis SET updated_at = now() - interval '1 hour' WHERE photo_id IN (?, ?)", photos[0], photos[1])

            // when — 아무것도 갱신되지 않았다
            orchestrator.sweep()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.PENDING)

            // 임베더가 다시 적재한 것처럼 갱신 시각을 올린다
            jdbcTemplate.update("UPDATE photo_analysis SET updated_at = now() WHERE photo_id IN (?, ?)", photos[0], photos[1])
            orchestrator.sweep()

            // then
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.DONE)
        }

        @Test
        fun `진행이 멈추면 다시 보내고 상한을 넘기면 실패로 닫는다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val jobId = requestFull()

            // when — 하트비트를 정체 판정보다 오래전으로
            stall(jobId)
            orchestrator.sweep()

            // then — 다시 보냈다
            assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED, AnalysisStage.EMBED)
            assertThat(job(jobId).stageAttempts).isEqualTo(2)

            // 상한까지 정체가 반복되면 포기한다
            stall(jobId)
            orchestrator.sweep()
            stall(jobId)
            orchestrator.sweep()
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(stages(jobId)).hasSize(3)
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job.error).contains("EMBED").contains("3회")
            }
        }

        private fun stall(jobId: Long) {
            jdbcTemplate.update("UPDATE ai_analysis_jobs SET heartbeat_at = now() - interval '30 minutes' WHERE id = ?", jobId)
        }
    }

    @Nested
    @DisplayName("옛 계약(status로 시작을 알리는 Lambda)일 때")
    inner class LegacyLambda {

        @Test
        fun `SCORE를 보낸 뒤 Lambda가 RUNNING으로 올리면 더 부르지 않고 DONE은 그대로 받아들인다`() {
            // given — 임베딩이 끝난 갤러리라 EMBED는 첫 스윕에서 닫힌다
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val jobId = requestFull()
            orchestrator.sweep()
            assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED, AnalysisStage.SCORE)

            // when — score가 잡을 열었다(status RUNNING). 스윕은 손대지 않는다
            jdbcTemplate.update("UPDATE ai_analysis_jobs SET status = 'RUNNING', started_at = now() WHERE id = ?", jobId)
            jdbcTemplate.update("UPDATE ai_analysis_jobs SET dispatched_at = now() - interval '1 hour' WHERE id = ?", jobId)
            orchestrator.sweep()
            assertThat(stages(jobId)).hasSize(2)

            // categorize가 체인 끝에서 DONE을 찍는다
            jdbcTemplate.update("UPDATE ai_analysis_jobs SET status = 'DONE', finished_at = now() WHERE id = ?", jobId)
            orchestrator.sweep()

            // then
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.DONE)
            assertThat(stages(jobId)).hasSize(2)
        }

        @Test
        fun `보낸 뒤 아무도 집지 않으면 재시도 창이 지난 뒤 다시 보내고 상한을 넘기면 실패다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val jobId = requestFull()
            orchestrator.sweep()
            assertThat(stages(jobId).last()).isEqualTo(AnalysisStage.SCORE)

            // when — 창 안에는 기다린다
            orchestrator.sweep()
            assertThat(stages(jobId)).hasSize(2)

            // 창이 지나면 다시 보낸다
            expireDispatch(jobId)
            orchestrator.sweep()
            assertThat(stages(jobId)).hasSize(3)
            assertThat(job(jobId).stageAttempts).isEqualTo(2)

            expireDispatch(jobId)
            orchestrator.sweep()
            expireDispatch(jobId)
            orchestrator.sweep()

            // then — 3회를 넘긴 네 번째는 포기
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(stages(jobId)).hasSize(4)
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job.error).contains("SCORE")
            }
        }

        @Test
        fun `NAMING은 CATEGORIZE 하나만 보낸다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            recommendationFixture.분석_잡(fixture.galleryId, mode = "FULL", status = "DONE")

            // when
            val jobId = analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.NAMING).jobId
            orchestrator.sweep()

            // then
            assertThat(stages(jobId)).containsExactly(AnalysisStage.CATEGORIZE)
        }

        @Test
        fun `호출이 실패하면 잡을 닫지 않고 다음 스윕이 바로 다시 보낸다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            stageInvoker.failNext = true
            val jobId = requestFull()
            assertThat(stages(jobId)).isEmpty()
            assertThat(job(jobId).stageStatus).isEqualTo(AnalysisStatus.PENDING)

            // when
            orchestrator.sweep()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED)
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.PENDING)
                softly.assertThat(job.stageAttempts).isEqualTo(2)
            }
        }

        @Test
        fun `같은 잡을 두 번 dispatch해도 한 번만 보낸다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val jobId = requestFull()

            // when
            orchestrator.dispatch(jobId)
            orchestrator.dispatch(jobId)

            // then
            assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED)
        }

        @Test
        fun `단계 없이 만들어진 옛 잡은 손대지 않는다`() {
            // given — V4 이전 행: stage NULL
            val jobId = recommendationFixture.분석_잡(fixture.galleryId, mode = "FULL", status = "PENDING")

            // when
            orchestrator.sweep()

            // then
            assertThat(stageInvoker.calls).isEmpty()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.PENDING)
        }

        private fun expireDispatch(jobId: Long) {
            jdbcTemplate.update("UPDATE ai_analysis_jobs SET dispatched_at = now() - interval '10 minutes' WHERE id = ?", jobId)
        }
    }

    @Nested
    @DisplayName("단계 상태를 쓰는 Lambda(Phase 0 이후)일 때")
    inner class ReportingLambda {

        private val reporting = AnalysisOrchestrator(
            analysisJobRepository,
            photoRepository,
            stageInvoker,
            AnalysisProperties(lambdaReportsStage = true),
            transactionTemplate,
            clock,
        )

        @Test
        fun `단계마다 부르고 DONE을 받으면 다음 단계로, 마지막이 끝나면 잡을 닫는다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val jobId = requestFull()
            reporting.sweep()
            assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED, AnalysisStage.SCORE)

            // when — score가 단계를 집고 끝낸다
            claimStage(jobId)
            reporting.sweep()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.RUNNING)
            assertThat(stages(jobId)).hasSize(2)
            finishStage(jobId, """{"score": {"processed": 1}}""")
            reporting.sweep()
            assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED, AnalysisStage.SCORE, AnalysisStage.CATEGORIZE)

            // categorize도 끝낸다
            claimStage(jobId)
            finishStage(jobId, """{"categorize": {"groups": 3}}""")
            reporting.sweep()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.DONE)
                softly.assertThat(job.stage).isEqualTo(AnalysisStage.CATEGORIZE)
                softly.assertThat(job.result).containsKeys("score", "categorize")
                softly.assertThat(stages(jobId)).hasSize(3)
            }
        }

        @Test
        fun `단계 FAILED는 잡 FAILED로 닫고, 하트비트가 끊기면 다시 보낸다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val jobId = requestFull()
            reporting.sweep()
            claimStage(jobId)

            // when — 하트비트가 정체 판정보다 오래전
            jdbcTemplate.update("UPDATE ai_analysis_jobs SET heartbeat_at = now() - interval '30 minutes' WHERE id = ?", jobId)
            reporting.sweep()
            assertThat(stages(jobId)).containsExactly(AnalysisStage.EMBED, AnalysisStage.SCORE, AnalysisStage.SCORE)

            // 다시 집은 Lambda가 실패를 남긴다
            claimStage(jobId)
            jdbcTemplate.update("UPDATE ai_analysis_jobs SET stage_status = 'FAILED', error = 'model load failed' WHERE id = ?", jobId)
            reporting.sweep()

            // then
            val job = job(jobId)
            assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
            assertThat(job.error).isEqualTo("model load failed")
        }

        private fun claimStage(jobId: Long) {
            jdbcTemplate.update(
                "UPDATE ai_analysis_jobs SET stage_status = 'RUNNING', heartbeat_at = now() WHERE id = ? AND stage_status = 'PENDING'",
                jobId,
            )
        }

        private fun finishStage(jobId: Long, partial: String) {
            jdbcTemplate.update(
                "UPDATE ai_analysis_jobs SET stage_status = 'DONE', result = COALESCE(result, '{}'::jsonb) || ?::jsonb WHERE id = ?",
                partial,
                jobId,
            )
        }
    }
}
