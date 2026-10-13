package com.soma.wes.analysis.service

import com.soma.wes.analysis.domain.AnalysisFailureCode
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.support.CategorizeStep
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationService
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
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

/**
 * 파이프라인 검증 — 요청 뒤 회차([AnalysisPipelineService.advance])를 돌려 잡이 상태를 지나가는지 본다. Lambda는 [FakeAiTaskSender]가 호출을 기록만 하고, Lambda·GPU 워커가 DB에 쓰는 일(벡터·점수·백분위·배정·error)은
 * 픽스처와 jdbc로 직접 흉내 낸다. 시간은 컬럼을 과거로 돌려 재현한다.
 */
@IntegrationTest
class AnalysisPipelineServiceTest @Autowired constructor(
    private val pipeline: AnalysisPipelineService,
    private val categorizeStep: CategorizeStep,
    private val analysisService: AnalysisService,
    private val analysisJobRepository: AnalysisJobRepository,
    private val conceptFolderRepository: ConceptFolderRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val aiTaskSender: FakeAiTaskSender,
    private val jdbcTemplate: JdbcTemplate,
    private val notifications: UserNotificationService,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        aiTaskSender.reset()
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
            pipeline.advance()
            assertThat(aiTaskSender.embedTasks.single().photoIds).containsExactlyElementsOf(photos)
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.ANALYZING)

            // 두 번째 스윕: 이미 배정된 사진은 다시 보내지 않는다
            pipeline.advance()
            assertThat(aiTaskSender.embedTasks).hasSize(1)

            // 임베더 역할: 벡터 적재 → 아직 점수가 없으니 기다린다(GPU 가 없어 score 폴백이 나간다)
            photos.forEach { photoFixture.벡터_적재(it, FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { v -> v[0] = 1f }) }
            pipeline.advance()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.ANALYZING)
            assertThat(aiTaskSender.scoreTasks.single().photoIds).containsExactlyElementsOf(photos)

            // score 역할: 점수 적재 → CATEGORIZING
            photos.forEach { photoFixture.점수_적재(it) }
            pipeline.advance()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.CATEGORIZING)
                softly.assertThat(job.attempts).isEqualTo(1)
                softly.assertThat(job.dispatchedAt).isNotNull()
                softly.assertThat(aiTaskSender.categorizeTasks.map { it.jobId }).containsExactly(jobId)
                softly.assertThat(aiTaskSender.categorizeTasks.single().galleryId).isEqualTo(fixture.galleryId)
            }
        }

        @Test
        fun `아직 올라오는 중인 PENDING 사진이 있으면 점수가 다 찼어도 기다린다`() {
            // given — 점수까지 있는 사진 하나 + 방금 발급된 PENDING 하나
            scoredPhotos(1)
            val pending = photoFixture.대기중_사진(fixture.galleryId, count = 1).single()
            val jobId = request()

            // when — 살아 있는 PENDING 은 곧 UPLOADED 가 될 사진이다
            pipeline.advance()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.ANALYZING)
            assertThat(aiTaskSender.categorizeTasks).isEmpty()

            // 발급이 오래되면 "올라오는 중"이 아니다 — 보정 스윕이 처리할 행이고 기대 장수에도 들지 않는다
            jdbcTemplate.update("UPDATE photos SET created_at = now() - interval '3 minutes' WHERE id = ?", pending)
            pipeline.advance()

            // then
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
            assertThat(aiTaskSender.categorizeTasks).hasSize(1)
        }

        @Test
        fun `실패로 표시된 사진은 기대 장수에서 빠져 나머지가 다 차면 넘어간다`() {
            // given
            scoredPhotos(2)
            val failed = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            photoFixture.분석_실패(failed)

            // when — 요청 뒤 첫 회차에서 이미 다 찼다
            val jobId = request()
            pipeline.advance()

            // then
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
            assertThat(aiTaskSender.categorizeTasks.map { it.jobId }).containsExactly(jobId)
        }

        @Test
        fun `대상 사진이 전부 사라지면 FAILED 로 닫는다`() {
            // given
            val photo = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            val jobId = request()
            photoFixture.분석_실패(photo, error = "EMBED_ATTEMPTS_EXCEEDED")

            // when
            pipeline.advance()

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

            // when — 첫 회차에서 폴백이 나가고, 두 번째는 간격 안이라 보내지 않는다
            request()
            pipeline.advance()
            pipeline.advance()

            // then
            assertThat(aiTaskSender.scoreTasks).hasSize(1)
            assertThat(aiTaskSender.scoreTasks.single().photoIds).containsExactlyElementsOf(photos)
        }

        @Test
        fun `두 스윕이 같은 잡을 동시에 밟아도 categorize 는 한 번만 나간다`() {
            // given
            scoredPhotos(2)
            val jobId = request()

            // when — 두 스레드가 categorize 단계를 동시에
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val steps = (1..2).map { pool.submit { barrier.await(5, TimeUnit.SECONDS); categorizeStep.advance() } }
                steps.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            // then — 조건부 UPDATE: 0을 받은 쪽은 EVENT 를 보내지 않는다
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
            assertThat(aiTaskSender.categorizeTasks).hasSize(1)
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
            pipeline.advance()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)

            // when — categorize 역할
            categorizeByLambda(jobId, photos)
            pipeline.advance()
            pipeline.advance()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.DONE)
                softly.assertThat(job.finishedAt).isNotNull()
                softly.assertThat(job.error).isNull()
                softly.assertThat(conceptFolderRepository.existsByGalleryIdAndAnalysisJobId(fixture.galleryId, jobId)).isTrue()
                softly.assertThat(completionNotificationsOf(fixture.photographer.requiredId)).isEqualTo(1)
                softly.assertThat(completionNotificationsOf(fixture.member.requiredId)).isEqualTo(1)
                softly.assertThat(aiTaskSender.categorizeTasks).hasSize(1)
            }
        }

        @Test
        fun `화질 점수와 백분위 없이 그룹만 와도 폴더를 만들고 DONE 으로 닫는다`() {
            // given — 1단계(CLIP)만 끝난 사진. 화질 점수(2단계)와 순위는 폴더 뒤에 찬다(#274 2물결)
            val photos = scoredPhotos(2)
            val jobId = request()
            pipeline.advance()

            // when — categorize 가 점수 없이 그룹·연사 묶음만 쓴다
            photos.forEach { photoFixture.그룹만_적재(it, embedGroupId = 1) }
            recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
            pipeline.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(job(jobId).status).isEqualTo(AnalysisStatus.DONE)
                softly.assertThat(conceptFolderRepository.existsByGalleryIdAndAnalysisJobId(fixture.galleryId, jobId)).isTrue()
                softly.assertThat(aiTaskSender.rankTasks).isEmpty()
            }
        }

        @Test
        fun `배정은 왔지만 임베딩 그룹이 덜 찼으면 기다린다`() {
            // given
            val photos = scoredPhotos(2)
            val jobId = request()
            pipeline.advance()
            recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
            photoFixture.백분위_적재(photos[0])

            // when
            pipeline.advance()

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
            pipeline.advance()
            assertThat(job(first).status).isEqualTo(AnalysisStatus.DONE)
            val folders = conceptFolderRepository.countByGalleryId(fixture.galleryId)

            // when — 같은 사진으로 다시 요청, categorize 는 같은 결과를 다시 남긴다
            val second = request()
            recommendationFixture.컨셉_배정(second, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
            pipeline.advance()

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
            pipeline.advance()

            // when
            jdbcTemplate.update("UPDATE analysis_jobs SET error = 'bedrock timeout', updated_at = now() WHERE id = ?", jobId)
            pipeline.advance()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job.error).isEqualTo("bedrock timeout")
                softly.assertThat(job.errorCode).isEqualTo(AnalysisFailureCode.CATEGORIZE_FAILED)
                softly.assertThat(job.finishedAt).isNotNull()
            }
        }

        @Test
        fun `요청에 실린 컨셉 수는 잡에 남고 categorize 를 처음 보낼 때와 다시 보낼 때 모두 실린다`() {
            // given
            scoredPhotos(1)
            val jobId = analysisService.request(fixture.galleryId, fixture.photographer.id!!, conceptCount = 4).jobId

            // when — 첫 전송, 타임아웃 뒤 재전송
            pipeline.advance()
            expireDispatch(jobId)
            pipeline.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(job(jobId).conceptCount).isEqualTo(4)
                softly.assertThat(aiTaskSender.categorizeTasks.map { it.conceptCount }).containsExactly(4, 4)
            }
        }

        @Test
        fun `컨셉 수 없이 요청하면 categorize 에도 실리지 않는다`() {
            scoredPhotos(1)
            val jobId = request()

            pipeline.advance()

            assertSoftly { softly ->
                softly.assertThat(job(jobId).conceptCount).isNull()
                softly.assertThat(aiTaskSender.categorizeTasks.single().conceptCount).isNull()
            }
        }

        @Test
        fun `시간 안에 결과가 없으면 다시 보내고 상한을 넘기면 FAILED 다`() {
            // given
            scoredPhotos(1)
            val jobId = request()
            pipeline.advance()
            assertThat(aiTaskSender.categorizeTasks).hasSize(1)

            // when — 타임아웃 안에는 기다린다
            pipeline.advance()
            assertThat(aiTaskSender.categorizeTasks).hasSize(1)

            // 타임아웃이 지나면 다시 보낸다
            expireDispatch(jobId)
            pipeline.advance()
            assertThat(aiTaskSender.categorizeTasks).hasSize(2)
            assertThat(job(jobId).attempts).isEqualTo(2)

            expireDispatch(jobId)
            pipeline.advance()
            expireDispatch(jobId)
            pipeline.advance()

            // then — 3회를 넘긴 네 번째는 포기
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.categorizeTasks).hasSize(3)
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job.error).contains("3회")
                softly.assertThat(job.errorCode).isEqualTo(AnalysisFailureCode.CATEGORIZE_TIMEOUT)
            }
        }

        @Test
        fun `호출이 실패하면 잡을 닫지 않고 전송 시각을 지워 folder 단계가 곧바로 다시 보낸다`() {
            // given
            scoredPhotos(1)
            val jobId = request()
            aiTaskSender.failNext = true

            // when — categorize 단계의 첫 전송이 실패하고, 같은 회차의 folder 단계가 기다리지 않고 다시 보낸다
            pipeline.advance()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(aiTaskSender.categorizeTasks).hasSize(1)
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.CATEGORIZING)
                softly.assertThat(job.attempts).isEqualTo(2)
                softly.assertThat(job.dispatchedAt).isNotNull()
            }
        }

        private fun expireDispatch(jobId: Long) {
            jdbcTemplate.update("UPDATE analysis_jobs SET dispatched_at = now() - interval '30 minutes' WHERE id = ?", jobId)
        }
    }

    @Nested
    @DisplayName("ANALYZING 의 진행이 멈췄을 때")
    inner class Stalled {

        @Test
        fun `뒤처진 사진이 적으면 그 사진만 떼어 내고 나머지로 계속 간다`() {
            // given — 점수가 찬 3장과, 벡터만 있고 점수가 오지 않는 1장
            scoredPhotos(3)
            val lagging = photoFixture.임베딩된_사진(fixture.galleryId, count = 1).single()
            val jobId = request()
            pipeline.advance()
            assertThat(job(jobId).progressAt).isNotNull()

            // when — 진행 없이 기준 시간이 지났다
            stallSince(jobId, minutes = 36)
            pipeline.advance()
            pipeline.advance()

            // then — 떼어 낸 사진은 실패로 남고, 잡은 나머지 3장으로 categorize 를 보낸다
            assertSoftly { softly ->
                softly.assertThat(analysisErrorOf(lagging)).isEqualTo(PhotoPipelineRepository.ANALYSIS_STALLED)
                softly.assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
                softly.assertThat(aiTaskSender.categorizeTasks).hasSize(1)
            }
        }

        @Test
        fun `뒤처진 사진이 많으면 사진은 두고 잡을 SCORE_STAGE_DOWN 으로 닫는다`() {
            // given — 절반이 점수를 받지 못했다. 사진이 아니라 실행기의 문제다
            scoredPhotos(2)
            val lagging = photoFixture.임베딩된_사진(fixture.galleryId, count = 2)
            val jobId = request()
            pipeline.advance()

            // when
            stallSince(jobId, minutes = 36)
            pipeline.advance()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job.errorCode).isEqualTo(AnalysisFailureCode.SCORE_STAGE_DOWN)
                softly.assertThat(lagging.map { analysisErrorOf(it) }).containsOnlyNulls()
            }
        }

        @Test
        fun `기준 시간 전에는 기다린다`() {
            // given
            scoredPhotos(3)
            val lagging = photoFixture.임베딩된_사진(fixture.galleryId, count = 1).single()
            val jobId = request()
            pipeline.advance()

            // when
            stallSince(jobId, minutes = 20)
            pipeline.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(job(jobId).status).isEqualTo(AnalysisStatus.ANALYZING)
                softly.assertThat(analysisErrorOf(lagging)).isNull()
            }
        }

        @Test
        fun `사진이 아직 올라오는 중이면 감시가 발동하지 않는다`() {
            // given
            scoredPhotos(3)
            val lagging = photoFixture.임베딩된_사진(fixture.galleryId, count = 1).single()
            val jobId = request()
            pipeline.advance()
            photoFixture.대기중_사진(fixture.galleryId, count = 1)

            // when
            stallSince(jobId, minutes = 36)
            pipeline.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(job(jobId).status).isEqualTo(AnalysisStatus.ANALYZING)
                softly.assertThat(analysisErrorOf(lagging)).isNull()
            }
        }

        @Test
        fun `새 사진이 올라오거나 점수가 붙으면 멈춘 시각을 다시 센다`() {
            // given
            scoredPhotos(3)
            val lagging = photoFixture.임베딩된_사진(fixture.galleryId, count = 1).single()
            val jobId = request()
            pipeline.advance()
            stallSince(jobId, minutes = 36)

            // when — 기준 시간이 지났지만 그 사이 새 사진이 올라왔다
            val late = photoFixture.임베딩된_사진(fixture.galleryId, count = 1).single()
            pipeline.advance()
            pipeline.advance()

            // then — 방금 올라온 사진을 "멈춘 사진"으로 떼어 내지 않는다
            assertSoftly { softly ->
                softly.assertThat(job(jobId).status).isEqualTo(AnalysisStatus.ANALYZING)
                softly.assertThat(listOf(lagging, late).map { analysisErrorOf(it) }).containsOnlyNulls()
                softly.assertThat(job(jobId).progressAt).isAfter(ZonedDateTime.now().minusMinutes(5))
            }
        }

        @Test
        fun `올라온 사진이 한 장도 없이 멈추면 NOTHING_TO_ANALYZE 로 닫는다`() {
            // given — 요청 뒤 올라온 사진은 지워졌고, 올라오지 않는 PENDING 만 남았다
            val uploaded = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val pending = photoFixture.대기중_사진(fixture.galleryId, count = 1)
            val jobId = request()
            jdbcTemplate.update("UPDATE photos SET deleted_at = now() WHERE id = ?", uploaded.single())
            jdbcTemplate.update("UPDATE photos SET created_at = now() - interval '1 hour' WHERE id = ?", pending.single())
            pipeline.advance()

            // when
            stallSince(jobId, minutes = 36)
            pipeline.advance()

            // then — 그 PENDING 이 휴지통으로 갈 때까지 하루를 기다리지 않는다
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job.errorCode).isEqualTo(AnalysisFailureCode.NOTHING_TO_ANALYZE)
            }
        }

        private fun stallSince(jobId: Long, minutes: Int) {
            jdbcTemplate.update("UPDATE analysis_jobs SET progress_at = now() - make_interval(mins => ?) WHERE id = ?", minutes, jobId)
        }
    }

    @Nested
    @DisplayName("CATEGORIZING 의 대기를 끝낼 때")
    inner class CategorizingLimits {

        @Test
        fun `categorize 를 보낸 뒤에 올라온 사진은 기다리지 않고 닫으며 그 사진은 폴더에 넣지 않는다`() {
            // given
            val photos = scoredPhotos(3)
            val jobId = request()
            pipeline.advance()
            assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)

            // when — 분류 중에 사진이 더 올라왔다(벡터까지 받았지만 이 categorize 는 보지 못했다)
            val late = photoFixture.임베딩된_사진(fixture.galleryId, count = 1).single()
            categorizeByLambda(jobId, photos)
            pipeline.advance()

            // then — 전에는 "전부 분류됨"이 거짓이 되어 한 시간 뒤 FAILED 였다
            assertSoftly { softly ->
                softly.assertThat(job(jobId).status).isEqualTo(AnalysisStatus.DONE)
                softly.assertThat(assignedPhotoIds()).containsExactlyInAnyOrderElementsOf(photos)
                softly.assertThat(assignedPhotoIds()).doesNotContain(late)
            }
        }

        @Test
        fun `CATEGORIZING 에 들어간 지 기한이 지나면 CATEGORIZE_TIMEOUT 으로 닫는다`() {
            // given — 방금 다시 보냈지만 잡 전체로는 기한을 넘겼다
            scoredPhotos(1)
            val jobId = request()
            pipeline.advance()
            jdbcTemplate.update("UPDATE analysis_jobs SET categorizing_at = now() - interval '71 minutes' WHERE id = ?", jobId)

            // when
            pipeline.advance()

            // then
            val job = job(jobId)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(job.errorCode).isEqualTo(AnalysisFailureCode.CATEGORIZE_TIMEOUT)
            }
        }

        private fun assignedPhotoIds(): List<Long> =
            jdbcTemplate.query("SELECT photo_id FROM detail_folder_assignments WHERE gallery_id = ?", { rs, _ -> rs.getLong(1) }, fixture.galleryId)
    }

    private fun analysisErrorOf(photoId: Long): String? =
        jdbcTemplate.queryForList("SELECT error FROM photo_analysis WHERE photo_id = ?", String::class.java, photoId).firstOrNull()
}
