package com.soma.wes.recommendation.service

import com.soma.wes.category.service.AiCategoryFolderService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.domain.AiJobStatus
import com.soma.wes.recommendation.domain.AiSelectionJob
import com.soma.wes.recommendation.domain.AiSelectionMode
import com.soma.wes.recommendation.dto.LlmPartDto
import com.soma.wes.recommendation.dto.request.AiRecommendationRequest
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.recommendation.repository.AiRecommendationRepository
import com.soma.wes.recommendation.repository.AiSelectionJobRepository
import com.soma.wes.recommendation.support.AiSelectionJobRecovery
import com.soma.wes.selection.fixture.SelectionFixture
import com.soma.wes.support.FakeStructuredLlmClient
import com.soma.wes.support.IntegrationTest
import com.soma.wes.support.ManualAiJobExecutor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

@IntegrationTest
class AiSelectionJobRunnerTest @Autowired constructor(
    private val runner: AiSelectionJobRunner,
    private val recovery: AiSelectionJobRecovery,
    private val aiRecommendationService: AiRecommendationService,
    private val aiCategoryFolderService: AiCategoryFolderService,
    private val aiSelectionJobRepository: AiSelectionJobRepository,
    private val aiRecommendationRepository: AiRecommendationRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val selectionFixture: SelectionFixture,
    private val llm: FakeStructuredLlmClient,
    private val executor: ManualAiJobExecutor,
    private val jdbcTemplate: JdbcTemplate,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        llm.reset()
        executor.reset()
        fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 4)
    }

    /**
     * 배경: 해변 폴더 6장(연사 2쌍 포함) + 정원 폴더 2장 + 세트 뒤에 분석된 미분류 1장.
     * 임베딩은 사진마다 다른 방향으로 넣어 MMR이 의미를 갖게 한다.
     */
    private fun world(): World {
        val beach = analyzedPhotos(count = 6, embedGroupId = 1, clusterOf = { i -> i / 2 }) // (0,1) (2,3) (4,5) 연사
        val garden = analyzedPhotos(count = 2, embedGroupId = 2, clusterOf = { i -> 10 + i })
        val jobId = recommendationFixture.분석_잡(fixture.galleryId)
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, parentName = "야외 자연", conceptName = "해변")
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 2, parentName = "야외 정원·건물", conceptName = "정원")
        val concepts = aiCategoryFolderService.createFromAnalysis(fixture.galleryId, fixture.photographer.id!!)
        val folderIdByName = concepts.flatMap { it.details }.associate { it.name to it.id }
        val unfiled = analyzedPhotos(count = 1, embedGroupId = 3, clusterOf = { 20 }).single()
        (beach + garden + unfiled).forEach { recommendationFixture.미리보기(it) }
        return World(beach, garden, unfiled, folderIdByName.getValue("해변"), folderIdByName.getValue("정원"))
    }

    private fun analyzedPhotos(count: Int, embedGroupId: Int, clusterOf: (Int) -> Int): List<Long> =
        photoFixture.업로드된_사진(fixture.galleryId, count).mapIndexed { i, photoId ->
            val vector = FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { it[(embedGroupId * 10 + clusterOf(i)) % it.size] = 1f }
            photoFixture.벡터_적재(photoId, vector)
            recommendationFixture.분석_결과(
                photoId,
                embedGroupId = embedGroupId,
                clusterId = clusterOf(i),
                clusterRank = i % 2,
                technicalPct = 90.0 - i * 5,
                aestheticPct = 80.0 - i * 3,
                sharpness = 100.0 + (count - i) * 10,
            )
            photoId
        }

    private data class World(
        val beach: List<Long>,
        val garden: List<Long>,
        val unfiled: Long,
        val beachFolderId: Long,
        val gardenFolderId: Long,
    )

    private fun requestJob(): Long =
        aiRecommendationService.request(fixture.galleryId, fixture.member.id!!, AiRecommendationRequest()).jobId

    private fun reasonsJson(photoIds: Collection<Long>, text: (Long) -> String = { "사진 $it 이유" }): String =
        photoIds.joinToString(",", prefix = """{"reasons":[""", postfix = "]}") { """{"photo_id":"$it","reason":"${text(it)}"}""" }

    @Nested
    @DisplayName("첫 라운드(draft)를 돌릴 때")
    inner class Draft {

        @Test
        fun `폴더마다 쿼터·연사 dedup으로 고르고 미분류는 folderId 없이 담는다`() {
            // given — 목표 4: 해변 6장 → round(4·6/9)=3 ≤ 3, 정원 2장 → 1, 미분류 1장 → 1
            val w = world()
            val jobId = requestJob()
            llm.respondWith(reasonsJson(w.beach + w.garden + w.unfiled))

            // when
            runner.run(jobId)

            // then
            val job = aiSelectionJobRepository.findById(jobId).orElseThrow()
            val recs = aiRecommendationRepository.findAllBySelectionIdAndRound(job.selectionId, 1)
            val beachPicks = recs.filter { it.folderId == w.beachFolderId }
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AiJobStatus.DONE)
                softly.assertThat(job.round).isEqualTo(1)
                softly.assertThat(beachPicks).hasSize(3)
                softly.assertThat(beachPicks.map { it.rank }).containsExactlyInAnyOrder(1, 2, 3)
                softly.assertThat(recs.filter { it.folderId == w.gardenFolderId }).hasSize(1)
                softly.assertThat(recs.filter { it.folderId == null }.map { it.photoId }).containsExactly(w.unfiled)
                softly.assertThat(recs.map { it.reason }).doesNotContainNull()
                softly.assertThat(recs.first().scoreBreakdown).containsKeys("pipeline", "folder_rank", "primary_reason", "facts")
                softly.assertThat(job.result).containsEntry("k", 5).containsEntry("unfiled", 1).containsKey("perFolder")
            }
        }

        @Test
        fun `SCORE만 끝나 백분위가 없는 사진은 재료에서 빠진다`() {
            // given — 벡터·model_version은 있지만 CATEGORIZE 전이라 백분위가 없는 사진 하나
            val w = world()
            val scoredOnly = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
            photoFixture.벡터_적재(scoredOnly, FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { it[99] = 1f })
            recommendationFixture.점수만(scoredOnly)
            recommendationFixture.미리보기(scoredOnly)
            val jobId = requestJob()
            llm.respondWith(reasonsJson(w.beach + w.garden + w.unfiled))

            // when
            runner.run(jobId)

            // then — 50점으로 메꿔 미분류에 끼워 넣지 않는다
            val job = aiSelectionJobRepository.findById(jobId).orElseThrow()
            val recs = aiRecommendationRepository.findAllBySelectionIdAndRound(job.selectionId, 1)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AiJobStatus.DONE)
                softly.assertThat(recs.map { it.photoId }).doesNotContain(scoredOnly)
                softly.assertThat(recs.filter { it.folderId == null }.map { it.photoId }).containsExactly(w.unfiled)
            }
        }

        @Test
        fun `이유는 2단계로 채우고 사진·형제를 함께 보낸다`() {
            // given
            val w = world()
            val jobId = requestJob()
            llm.respondWith(reasonsJson(w.beach + w.garden + w.unfiled))

            // when
            runner.run(jobId)

            // then — 요청마다 이 컷 이미지가 있고, 연사가 있는 컷은 형제 이미지가 붙는다
            val parts = llm.requests.flatMap { it.parts }
            val texts = parts.filterIsInstance<LlmPartDto.Text>().map { it.text }
            assertSoftly { softly ->
                softly.assertThat(llm.calls).isGreaterThanOrEqualTo(1)
                softly.assertThat(texts).anyMatch { it.startsWith("### photo_id:") }
                softly.assertThat(texts).anyMatch { it.startsWith("같은 순간의 다른 컷") }
                softly.assertThat(parts.filterIsInstance<LlmPartDto.Image>()).isNotEmpty()
                softly.assertThat(llm.requests.first().maxRetries).isEqualTo(2)
            }
        }

        @Test
        fun `LLM 실패·상한 초과·누락은 템플릿 문장으로 채운다`() {
            // given — 해변 첫 장만 1000자로 답하고 나머지는 빠뜨린다
            val w = world()
            val jobId = requestJob()
            llm.respondWith(reasonsJson(listOf(w.beach[0])) { "가".repeat(1000) })

            // when
            runner.run(jobId)

            // then
            val job = aiSelectionJobRepository.findById(jobId).orElseThrow()
            val recs = aiRecommendationRepository.findAllBySelectionIdAndRound(job.selectionId, 1)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AiJobStatus.DONE)
                softly.assertThat(recs.map { it.reason }).doesNotContainNull()
                softly.assertThat(recs.map { it.reason!! }).allMatch { it.length <= 200 }
            }
        }

        @Test
        fun `LLM이 꺼져 있으면 부르지 않고 템플릿으로만 채운다`() {
            // given
            world()
            llm.isEnabled = false
            val jobId = requestJob()

            // when
            runner.run(jobId)

            // then
            val job = aiSelectionJobRepository.findById(jobId).orElseThrow()
            val recs = aiRecommendationRepository.findAllBySelectionIdAndRound(job.selectionId, 1)
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AiJobStatus.DONE)
                softly.assertThat(llm.calls).isZero()
                softly.assertThat(recs.map { it.reason }).doesNotContainNull()
                softly.assertThat(job.result).containsEntry("llm", false)
            }
        }
    }

    @Nested
    @DisplayName("다음 라운드(refine)를 돌릴 때")
    inner class Refine {

        @Test
        fun `목표를 채워도 폴더마다 1장은 남기고 담은 사진은 뺀다`() {
            // given — 목표 4장을 이미 담았다
            val w = world()
            llm.isEnabled = false
            val first = requestJob()
            runner.run(first)
            val selectionId = selectionFixture.셀렉(fixture.galleryId)
            selectionFixture.담긴_사진(selectionId, w.beach.take(3) + w.garden.take(1))
            val second = requestJob()

            // when
            runner.run(second)

            // then
            val job = aiSelectionJobRepository.findById(second).orElseThrow()
            val recs = aiRecommendationRepository.findAllBySelectionIdAndRound(selectionId, 2)
            assertSoftly { softly ->
                softly.assertThat(job.round).isEqualTo(2)
                softly.assertThat(recs.filter { it.folderId == w.beachFolderId }).hasSize(1)
                softly.assertThat(recs.filter { it.folderId == w.gardenFolderId }).hasSize(1)
                softly.assertThat(recs.map { it.photoId }).doesNotContainAnyElementsOf(w.beach.take(3) + w.garden.take(1))
            }
        }

        @Test
        fun `거절한 사진은 빠지고 거절하지 않은 이전 노출은 다시 나올 수 있다`() {
            // given
            val w = world()
            llm.isEnabled = false
            val first = requestJob()
            runner.run(first)
            val selectionId = selectionFixture.셀렉(fixture.galleryId)
            val firstRound = aiRecommendationRepository.findAllBySelectionIdAndRound(selectionId, 1)
            val rejected = firstRound.first { it.folderId == w.beachFolderId }.photoId
            jdbcTemplate.update("UPDATE ai_recommendations SET rejected_at = now() WHERE photo_id = ?", rejected)
            val second = requestJob()

            // when
            runner.run(second)

            // then
            val secondRound = aiRecommendationRepository.findAllBySelectionIdAndRound(selectionId, 2)
            val shownAgain = firstRound.map { it.photoId }.filter { it != rejected }
            assertSoftly { softly ->
                softly.assertThat(secondRound.map { it.photoId }).doesNotContain(rejected)
                softly.assertThat(secondRound.map { it.photoId }).containsAnyElementsOf(shownAgain)
            }
        }
    }

    @Nested
    @DisplayName("잡 생애를 다룰 때")
    inner class Lifecycle {

        @Test
        fun `요청은 커밋 뒤 실행기에 넘기고 실행기가 돌면 DONE이 된다`() {
            // given
            world()
            llm.isEnabled = false

            // when
            val jobId = requestJob()
            val before = aiSelectionJobRepository.findById(jobId).orElseThrow().status
            executor.runAll()

            // then
            assertThat(before).isEqualTo(AiJobStatus.PENDING)
            assertThat(aiSelectionJobRepository.findById(jobId).orElseThrow().status).isEqualTo(AiJobStatus.DONE)
        }

        @Test
        fun `이미 집힌 잡은 다시 돌지 않는다`() {
            // given
            world()
            llm.isEnabled = false
            val jobId = requestJob()
            runner.run(jobId)
            val finishedAt = aiSelectionJobRepository.findById(jobId).orElseThrow().finishedAt

            // when
            runner.run(jobId)

            // then
            assertThat(aiSelectionJobRepository.findById(jobId).orElseThrow().finishedAt).isEqualTo(finishedAt)
        }

        @Test
        fun `기동 복구는 RUNNING 고아를 PENDING으로 되돌려 다시 줄에 세운다`() {
            // given — 이전 프로세스가 집었다가 죽은 잡(이 프로세스의 실행기는 모른다)
            world()
            val selectionId = selectionFixture.셀렉(fixture.galleryId)
            val orphan = aiSelectionJobRepository.saveAndFlush(
                AiSelectionJob(selectionId = selectionId, mode = AiSelectionMode.DRAFT, folderSetJobId = null),
            )
            jdbcTemplate.update("UPDATE ai_selection_jobs SET status = 'RUNNING', started_at = now() WHERE id = ?", orphan.requiredId)
            executor.reset()

            // when
            recovery.recoverOnStartup()

            // then
            assertThat(aiSelectionJobRepository.findById(orphan.requiredId).orElseThrow().status).isEqualTo(AiJobStatus.PENDING)
            assertThat(executor.pendingCount).isEqualTo(1)
        }

        @Test
        fun `세트가 비면 FAILED로 남고 예외는 밖으로 새지 않는다`() {
            // given — 세트 폴더는 있지만 분석된 사진이 없다
            val photos = photoFixture.임베딩된_사진(fixture.galleryId, 1)
            photos.forEach { recommendationFixture.분석_결과(it, embedGroupId = 1) }
            val analysisJobId = recommendationFixture.분석_잡(fixture.galleryId)
            recommendationFixture.컨셉_배정(analysisJobId, fixture.galleryId, embedGroupId = 1, parentName = "야외 자연", conceptName = "해변")
            aiCategoryFolderService.createFromAnalysis(fixture.galleryId, fixture.photographer.id!!)
            val jobId = requestJob()
            jdbcTemplate.update("UPDATE photo_analysis SET model_version = NULL")

            // when
            runner.run(jobId)

            // then
            val job = aiSelectionJobRepository.findById(jobId).orElseThrow()
            assertThat(job.status).isEqualTo(AiJobStatus.FAILED)
            assertThat(job.error).contains("분석 결과가 없다")
        }
    }

    @Test
    fun `세트 없이 요청하면 여전히 409다`() {
        // 실행기가 생겨도 요청 검증은 그대로다 — 폴더 생성이 먼저다.
        org.assertj.core.api.Assertions.assertThatThrownBy {
            aiRecommendationService.request(fixture.galleryId, fixture.member.id!!, AiRecommendationRequest())
        }
            .isInstanceOf(RecommendationException::class.java)
            .extracting("errorCode")
            .isEqualTo(RecommendationErrorCode.FOLDER_SET_NOT_READY)
    }
}
