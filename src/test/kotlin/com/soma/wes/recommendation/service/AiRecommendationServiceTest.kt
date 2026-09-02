package com.soma.wes.recommendation.service

import com.soma.wes.category.service.AiCategoryFolderService
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.domain.AiJobStatus
import com.soma.wes.recommendation.domain.AiSelectionMode
import com.soma.wes.recommendation.dto.request.AiRecommendationRequest
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import com.soma.wes.selection.fixture.SelectionFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

@IntegrationTest
class AiRecommendationServiceTest @Autowired constructor(
    private val aiRecommendationService: AiRecommendationService,
    private val aiCategoryFolderService: AiCategoryFolderService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val selectionFixture: SelectionFixture,
    private val jdbcTemplate: JdbcTemplate,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    /** 정상 경로의 배경: 임베딩 → 분석 → 배정 → AI 폴더 세트. 세트 키와 자식 폴더·사진을 돌려준다. */
    private fun aiFolderSet(photoCount: Int = 2): AiSet {
        val photoIds = photoFixture.임베딩된_사진(fixture.galleryId, count = photoCount)
        photoIds.forEach { recommendationFixture.분석_결과(it, embedGroupId = 1) }
        val jobId = recommendationFixture.분석_잡(fixture.galleryId)
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, parentName = "야외 자연", conceptName = "해변")

        val concepts = aiCategoryFolderService.createFromAnalysis(fixture.galleryId, fixture.photographer.id!!)
        return AiSet(
            analysisJobId = jobId,
            folderId = concepts.single().details.single().id,
            photoIds = photoIds,
        )
    }

    private data class AiSet(
        val analysisJobId: Long,
        val folderId: Long,
        val photoIds: List<Long>,
    )

    @Nested
    @DisplayName("추천을 요청할 때")
    inner class Request {

        @Test
        fun `AI 폴더 세트가 있으면 PENDING 잡을 만든다`() {
            // given
            val set = aiFolderSet()

            // when
            val response = aiRecommendationService.request(
                fixture.galleryId, fixture.member.id!!, AiRecommendationRequest(),
            )

            // then — 셀렉 행이 없었으므로 함께 생기고, 기준 세트는 최신 세트다.
            assertSoftly { softly ->
                softly.assertThat(response.status).isEqualTo(AiJobStatus.PENDING)
                softly.assertThat(response.mode).isEqualTo(AiSelectionMode.DRAFT)
                softly.assertThat(response.folderSetJobId).isEqualTo(set.analysisJobId)
                softly.assertThat(response.round).isNull()
                softly.assertThat(response.error).isNull()
            }
        }

        @Test
        fun `모드는 DB에 소문자로 저장된다`() {
            // AI 워커가 mode == "draft" 문자열로 분기한다 — enum 이름(DRAFT)이 새면 워커가 refine으로 돈다.
            // given
            aiFolderSet()

            // when
            aiRecommendationService.request(fixture.galleryId, fixture.member.id!!, AiRecommendationRequest())

            // then
            val mode = jdbcTemplate.queryForObject("SELECT mode FROM ai_selection_jobs", String::class.java)
            assertThat(mode).isEqualTo("draft")
        }

        @Test
        fun `추천 이력이 있으면 REFINE으로 만든다`() {
            // given
            val set = aiFolderSet()
            val first = aiRecommendationService.request(fixture.galleryId, fixture.member.id!!, AiRecommendationRequest())
            recommendationFixture.추천_잡_완료(first.jobId, round = 1)
            recommendationFixture.추천(first.selectionId, set.photoIds[0], round = 1, folderId = set.folderId)

            // when
            val second = aiRecommendationService.request(fixture.galleryId, fixture.member.id!!, AiRecommendationRequest())

            // then
            assertThat(second.mode).isEqualTo(AiSelectionMode.REFINE)
        }

        @Test
        fun `AI 폴더 세트가 없으면 거절한다`() {
            // 폴더 없는 갤러리에 폴백을 두면 "폴더별 추천"이 두 가지 모양이 된다 — 폴더 생성이 먼저다.
            // when & then
            assertThatThrownBy {
                aiRecommendationService.request(fixture.galleryId, fixture.member.id!!, AiRecommendationRequest())
            }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.FOLDER_SET_NOT_READY)
        }

        @Test
        fun `콕 집은 세트가 갤러리에 없으면 404다`() {
            // given
            aiFolderSet()

            // when & then
            assertThatThrownBy {
                aiRecommendationService.request(
                    fixture.galleryId, fixture.member.id!!, AiRecommendationRequest(analysisJobId = 999_999),
                )
            }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.FOLDER_SET_NOT_FOUND)
        }

        @Test
        fun `진행 중인 추천 잡이 있으면 거절한다`() {
            // given
            aiFolderSet()
            aiRecommendationService.request(fixture.galleryId, fixture.member.id!!, AiRecommendationRequest())

            // when & then
            assertThatThrownBy {
                aiRecommendationService.request(fixture.galleryId, fixture.member.id!!, AiRecommendationRequest())
            }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.SELECTION_JOB_ALREADY_ACTIVE)
        }

        @Test
        fun `제출된 앨범에는 걸지 않는다`() {
            // given
            val set = aiFolderSet()
            val selectionId = selectionFixture.셀렉(fixture.galleryId)
            selectionFixture.담긴_사진(selectionId, listOf(set.photoIds[0]))
            jdbcTemplate.update(
                "UPDATE photo_selections SET status = 'SUBMITTED', submitted_at = now() WHERE id = ?",
                selectionId,
            )

            // when & then
            assertThatThrownBy {
                aiRecommendationService.request(fixture.galleryId, fixture.member.id!!, AiRecommendationRequest())
            }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.SELECTION_ALREADY_SUBMITTED)
        }

        @Test
        fun `부부가 아니면 거절한다`() {
            // 추천은 부부의 선택을 돕는 초안이다 — 작가는 조회(GET)만 한다.
            // given
            aiFolderSet()

            // when & then
            assertThatThrownBy {
                aiRecommendationService.request(fixture.galleryId, fixture.photographer.id!!, AiRecommendationRequest())
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Nested
    @DisplayName("추천을 조회할 때")
    inner class ListRecommendations {

        @Test
        fun `셀렉이나 추천이 없으면 빈 응답이지 404가 아니다`() {
            // when
            val response = aiRecommendationService.list(fixture.galleryId, fixture.photographer.id!!, folderId = null)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.round).isNull()
                softly.assertThat(response.job).isNull()
                softly.assertThat(response.photos).isEmpty()
            }
        }

        @Test
        fun `최신 라운드만 돌려주고 순위 순서를 지킨다`() {
            // given
            val set = aiFolderSet(photoCount = 3)
            val selectionId = selectionFixture.셀렉(fixture.galleryId)
            recommendationFixture.추천(selectionId, set.photoIds[0], round = 1, rank = 1, folderId = set.folderId)
            recommendationFixture.추천(selectionId, set.photoIds[1], round = 2, rank = 1, folderId = set.folderId)
            recommendationFixture.추천(selectionId, set.photoIds[2], round = 2, rank = 2, folderId = set.folderId)

            // when
            val response = aiRecommendationService.list(fixture.galleryId, fixture.member.id!!, folderId = null)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.round).isEqualTo(2)
                softly.assertThat(response.photos.map { it.photo.photoId })
                    .containsExactly(set.photoIds[1], set.photoIds[2])
                softly.assertThat(response.photos.map { it.rank }).containsExactly(1, 2)
            }
        }

        @Test
        fun `folderId를 주면 그 폴더의 추천만 온다`() {
            // given — 미분류(folder_id null) 추천이 섞여 있어도 폴더 화면에는 안 나온다.
            val set = aiFolderSet(photoCount = 2)
            val selectionId = selectionFixture.셀렉(fixture.galleryId)
            recommendationFixture.추천(selectionId, set.photoIds[0], rank = 1, folderId = set.folderId)
            recommendationFixture.추천(selectionId, set.photoIds[1], rank = 1, folderId = null)

            // when
            val response = aiRecommendationService.list(fixture.galleryId, fixture.member.id!!, folderId = set.folderId)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.photos).hasSize(1)
                softly.assertThat(response.photos.single().photo.photoId).isEqualTo(set.photoIds[0])
                softly.assertThat(response.photos.single().folderId).isEqualTo(set.folderId)
            }
        }

        @Test
        fun `이유가 채워지면 reasonReady가 뒤집힌다`() {
            // 2단계 적재 — 추천 표시가 먼저 생기고 LLM 문장이 뒤이어 UPDATE 된다.
            // given
            val set = aiFolderSet()
            val selectionId = selectionFixture.셀렉(fixture.galleryId)
            recommendationFixture.추천(selectionId, set.photoIds[0], folderId = set.folderId)

            val before = aiRecommendationService.list(fixture.galleryId, fixture.member.id!!, folderId = null)
            recommendationFixture.추천_이유(selectionId, set.photoIds[0], round = 1, reason = "폴더 1위의 선명한 컷이에요.")

            // when
            val after = aiRecommendationService.list(fixture.galleryId, fixture.member.id!!, folderId = null)

            // then
            assertSoftly { softly ->
                softly.assertThat(before.photos.single().reasonReady).isFalse()
                softly.assertThat(before.photos.single().reason).isNull()
                softly.assertThat(after.photos.single().reasonReady).isTrue()
                softly.assertThat(after.photos.single().reason).contains("선명한")
            }
        }

        @Test
        fun `담긴 사진은 selected로 표시된다`() {
            // given
            val set = aiFolderSet(photoCount = 2)
            val selectionId = selectionFixture.셀렉(fixture.galleryId)
            selectionFixture.담긴_사진(selectionId, listOf(set.photoIds[0]))
            recommendationFixture.추천(selectionId, set.photoIds[0], rank = 1, folderId = set.folderId)
            recommendationFixture.추천(selectionId, set.photoIds[1], rank = 2, folderId = set.folderId)

            // when
            val response = aiRecommendationService.list(fixture.galleryId, fixture.member.id!!, folderId = null)

            // then
            assertThat(response.photos.map { it.selected }).containsExactly(true, false)
        }

        @Test
        fun `가장 최근 잡의 상태가 함께 온다`() {
            // given
            val set = aiFolderSet()
            val job = aiRecommendationService.request(fixture.galleryId, fixture.member.id!!, AiRecommendationRequest())

            // when
            val response = aiRecommendationService.list(fixture.galleryId, fixture.member.id!!, folderId = null)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.job?.jobId).isEqualTo(job.jobId)
                softly.assertThat(response.job?.status).isEqualTo(AiJobStatus.PENDING)
                softly.assertThat(response.job?.folderSetJobId).isEqualTo(set.analysisJobId)
            }
        }
    }
}
