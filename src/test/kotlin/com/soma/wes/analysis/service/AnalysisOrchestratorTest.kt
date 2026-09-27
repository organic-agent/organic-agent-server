package com.soma.wes.analysis.service

import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationService
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.FakeStageInvoker
import com.soma.wes.support.IntegrationTest
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

/**
 * 상태 기계 검증. Lambda는 [FakeStageInvoker]가 호출을 기록만 하고, Lambda·GPU 워커가 DB에 쓰는 일(벡터·점수·백분위·배정·error)은
 * 픽스처와 jdbc로 직접 흉내 낸다. 시간은 컬럼을 과거로 돌려 재현한다.
 */
@IntegrationTest
class AnalysisOrchestratorTest @Autowired constructor(
    private val orchestrator: AnalysisOrchestrator,
    private val analysisService: AnalysisService,
    private val analysisJobRepository: AnalysisJobRepository,
    private val conceptFolderRepository: ConceptFolderRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val stageInvoker: FakeStageInvoker,
    private val jdbcTemplate: JdbcTemplate,
    private val notifications: UserNotificationService,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        stageInvoker.reset()
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    private fun request(): Long = analysisService.request(fixture.galleryId, fixture.photographer.id!!).jobId

    private fun job(jobId: Long): AnalysisJob = analysisJobRepository.findById(jobId).orElseThrow()

    /** 벡터·점수까지 있는 사진 — 잡이 바로 CATEGORIZING 으로 갈 수 있는 상태. */
    private fun scoredPhotos(count: Int): List<Long> =
        photoFixture.임베딩된_사진(fixture.galleryId, count).onEach { photoFixture.점수_적재(it) }

    /** categorize Lambda 역할 — 백분위·그룹을 채우고 잡의 배정을 남긴다. */
    private fun categorizeByLambda(jobId: Long, photos: List<Long>, embedGroupId: Int = 1) {
        photos.forEach { photoFixture.백분위_적재(it, embedGroupId) }
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = embedGroupId, conceptName = "야외 자연", detailName = "해변")
    }

    private fun completionNotificationsOf(userId: Long): Int =
        notifications.list(userId, null, null).count { it.type == UserNotificationType.ANALYSIS_COMPLETED }

    @Nested
    @DisplayName("ANALYZING 에서")
    inner class Analyzing {

        @Test
        fun `올라온 사진은 잡과 무관하게 배정되고 점수가 다 차면 CATEGORIZING 으로 넘어간다`() {
            // given — 업로드만 된 사진 둘
            val photos = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val jobId = request()

            // when — 첫 스윕: 임베더에 배정, 잡은 기다린다
            orchestrator.sweep()
            assertThat(stageInvoker.embedCalls.single().photoIds).containsExactlyElementsOf(photos)
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.ANALYZING)

            // 두 번째 스윕: 이미 배정된 사진은 다시 보내지 않는다
            orchestrator.sweep()
            assertThat(stageInvoker.embedCalls).hasSize(1)

            // 임베더 역할: 벡터 적재 → 아직 점수가 없으니 기다린다(GPU 가 없어 score 폴백이 나간다)
            photos.forEach { photoFixture.벡터_적재(it, FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { v -> v[0] = 1f }) }
            orchestrator.sweep()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.ANALYZING)
            assertThat(stageInvoker.scoreCalls.single().photoIds).containsExactlyElementsOf(photos)

            // score 역할: 점수 적재 → CATEGORIZING
            photos.forEach { photoFixture.점수_적재(it) }
            orchestrator.sweep()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.CATEGORIZING)
                softly.assertThat(job.attempts).isEqualTo(1)
                softly.assertThat(job.dispatchedAt).isNotNull()
                softly.assertThat(stageInvoker.categorizeCalls.map { it.jobId }).containsExactly(jobId)
                softly.assertThat(stageInvoker.categorizeCalls.single().galleryId).isEqualTo(fixture.galleryId)
            }
        }

        @Test
        fun `아직 올라오는 중인 PENDING 사진이 있으면 점수가 다 찼어도 기다린다`() {
            // given — 점수까지 있는 사진 하나 + 방금 발급된 PENDING 하나
            scoredPhotos(1)
            val pending = photoFixture.대기중_사진(fixture.galleryId, count = 1).single()
            val jobId = request()

            // when — 살아 있는 PENDING 은 곧 UPLOADED 가 될 사진이다
            orchestrator.sweep()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.ANALYZING)
            assertThat(stageInvoker.categorizeCalls).isEmpty()

            // 발급이 오래되면 "올라오는 중"이 아니다 — 보정 스윕이 처리할 행이고 기대 장수에도 들지 않는다
            jdbcTemplate.update("UPDATE photos SET created_at = now() - interval '3 minutes' WHERE id = ?", pending)
            orchestrator.sweep()

            // then
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
            assertThat(stageInvoker.categorizeCalls).hasSize(1)
        }

        @Test
        fun `실패로 표시된 사진은 기대 장수에서 빠져 나머지가 다 차면 넘어간다`() {
            // given
            scoredPhotos(2)
            val failed = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            photoFixture.분석_실패(failed)

            // when — 요청 직후 한 걸음에서 이미 다 찼다
            val jobId = request()

            // then
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
            assertThat(stageInvoker.categorizeCalls.map { it.jobId }).containsExactly(jobId)
        }

        @Test
        fun `대상 사진이 전부 사라지면 FAILED 로 닫는다`() {
            // given
            val photo = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            val jobId = request()
            photoFixture.분석_실패(photo, error = "EMBED_ATTEMPTS_EXCEEDED")

            // when
            orchestrator.sweep()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job.error).contains("분석할 사진이 없습니다")
                softly.assertThat(job.finishedAt).isNotNull()
            }
        }

        @Test
        fun `GPU 가 없으면 벡터만 있는 사진을 score 폴백으로 보내되 같은 갤러리는 간격 안에 다시 보내지 않는다`() {
            // given — 벡터는 있고 점수는 없는 사진 셋
            val photos = photoFixture.임베딩된_사진(fixture.galleryId, count = 3)

            // when — 요청 직후 한 걸음에서 폴백이 나간다
            request()
            orchestrator.sweep()
            orchestrator.sweep()

            // then
            assertThat(stageInvoker.scoreCalls).hasSize(1)
            assertThat(stageInvoker.scoreCalls.single().photoIds).containsExactlyElementsOf(photos)
        }

        @Test
        fun `두 스윕이 같은 잡을 동시에 밟아도 categorize 는 한 번만 나간다`() {
            // given
            scoredPhotos(2)
            // 요청의 afterCommit 걸음이 먼저 넘기지 않도록 실행기를 한 번 실패시켜 잡을 ANALYZING 에 남겨 둔다
            stageInvoker.failNext = true
            val jobId = request()
            jdbcTemplate.update("UPDATE analysis_jobs SET status = 'ANALYZING', attempts = 0, dispatched_at = NULL WHERE id = ?", jobId)

            // when — 두 스레드가 같은 걸음을 동시에
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val steps = (1..2).map { pool.submit { barrier.await(5, TimeUnit.SECONDS); orchestrator.dispatch(jobId) } }
                steps.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            // then — CAS: 진 쪽은 EVENT 를 보내지 않는다
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
            assertThat(stageInvoker.categorizeCalls).hasSize(1)
            assertThat(job(jobId).attempts).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("CATEGORIZING 에서")
    inner class Categorizing {

        @Test
        fun `배정과 백분위가 오면 폴더를 만들고 DONE 으로 닫으며 알림을 한 번 보낸다`() {
            // given
            val photos = scoredPhotos(3)
            val jobId = request()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)

            // when — categorize 역할
            categorizeByLambda(jobId, photos)
            orchestrator.sweep()
            orchestrator.sweep()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.DONE)
                softly.assertThat(job.finishedAt).isNotNull()
                softly.assertThat(job.error).isNull()
                softly.assertThat(conceptFolderRepository.existsByGalleryIdAndAnalysisJobId(fixture.galleryId, jobId)).isTrue()
                softly.assertThat(completionNotificationsOf(fixture.photographer.requiredId)).isEqualTo(1)
                softly.assertThat(completionNotificationsOf(fixture.member.requiredId)).isEqualTo(1)
                softly.assertThat(stageInvoker.categorizeCalls).hasSize(1)
            }
        }

        @Test
        fun `배정은 왔지만 백분위가 덜 찼으면 기다린다`() {
            // given
            val photos = scoredPhotos(2)
            val jobId = request()
            recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
            photoFixture.백분위_적재(photos[0])

            // when
            orchestrator.sweep()

            // then
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
            assertThat(conceptFolderRepository.countByGalleryId(fixture.galleryId)).isZero()
        }

        @Test
        fun `새로 넣을 사진이 없으면 폴더 추가 없이 DONE 이고 알림도 없다`() {
            // given — 첫 잡이 폴더를 만들었다
            val photos = scoredPhotos(2)
            val first = request()
            categorizeByLambda(first, photos)
            orchestrator.sweep()
            assertThat(job(first).status).isEqualTo(AnalysisStatus.DONE)
            val folders = conceptFolderRepository.countByGalleryId(fixture.galleryId)

            // when — 같은 사진으로 다시 요청, categorize 는 같은 결과를 다시 남긴다
            val second = request()
            recommendationFixture.컨셉_배정(second, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
            orchestrator.sweep()

            // then
            assertSoftly { softly ->
                softly.assertThat(job(second).status).isEqualTo(AnalysisStatus.DONE)
                softly.assertThat(conceptFolderRepository.countByGalleryId(fixture.galleryId)).isEqualTo(folders)
                softly.assertThat(completionNotificationsOf(fixture.photographer.requiredId)).isEqualTo(1)
            }
        }

        @Test
        fun `Lambda 가 error 를 남기면 FAILED 로 닫는다`() {
            // given
            scoredPhotos(1)
            val jobId = request()

            // when
            jdbcTemplate.update("UPDATE analysis_jobs SET error = 'bedrock timeout', updated_at = now() WHERE id = ?", jobId)
            orchestrator.sweep()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job.error).isEqualTo("bedrock timeout")
                softly.assertThat(job.finishedAt).isNotNull()
            }
        }

        @Test
        fun `시간 안에 결과가 없으면 다시 보내고 상한을 넘기면 FAILED 다`() {
            // given
            scoredPhotos(1)
            val jobId = request()
            assertThat(stageInvoker.categorizeCalls).hasSize(1)

            // when — 타임아웃 안에는 기다린다
            orchestrator.sweep()
            assertThat(stageInvoker.categorizeCalls).hasSize(1)

            // 타임아웃이 지나면 다시 보낸다
            expireDispatch(jobId)
            orchestrator.sweep()
            assertThat(stageInvoker.categorizeCalls).hasSize(2)
            assertThat(job(jobId).attempts).isEqualTo(2)

            expireDispatch(jobId)
            orchestrator.sweep()
            expireDispatch(jobId)
            orchestrator.sweep()

            // then — 3회를 넘긴 네 번째는 포기
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(stageInvoker.categorizeCalls).hasSize(3)
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job.error).contains("3회")
            }
        }

        @Test
        fun `호출이 실패하면 잡을 닫지 않고 다음 스윕이 바로 다시 보낸다`() {
            // given
            scoredPhotos(1)
            stageInvoker.failNext = true
            val jobId = request()
            assertThat(stageInvoker.categorizeCalls).isEmpty()
            assertThat(job(jobId).dispatchedAt).isNull()

            // when
            orchestrator.sweep()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(stageInvoker.categorizeCalls).hasSize(1)
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.CATEGORIZING)
                softly.assertThat(job.attempts).isEqualTo(2)
                softly.assertThat(job.dispatchedAt).isNotNull()
            }
        }

        private fun expireDispatch(jobId: Long) {
            jdbcTemplate.update("UPDATE analysis_jobs SET dispatched_at = now() - interval '30 minutes' WHERE id = ?", jobId)
        }
    }
}
