package com.soma.wes.recommendation.service

import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.domain.AiAnalysisMode
import com.soma.wes.recommendation.domain.AiJobStatus
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.repository.AiAnalysisJobRepository
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
class AiAnalysisServiceTest @Autowired constructor(
    private val aiAnalysisService: AiAnalysisService,
    private val aiAnalysisJobRepository: AiAnalysisJobRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val jdbcTemplate: JdbcTemplate,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("분석을 요청할 때")
    inner class Request {

        @Test
        fun `임베딩이 끝난 사진이 있으면 PENDING 잡을 만든다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 2)

            // when
            val response = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.galleryId).isEqualTo(fixture.galleryId)
                softly.assertThat(response.status).isEqualTo(AiJobStatus.PENDING)
                softly.assertThat(response.mode).isEqualTo(AiAnalysisMode.FULL)
                softly.assertThat(response.startedAt).isNull()
                softly.assertThat(response.error).isNull()
            }
            assertThat(aiAnalysisJobRepository.findById(response.jobId)).isPresent()
        }

        @Test
        fun `벡터가 없는 갤러리는 거절한다`() {
            // 업로드만 끝난 사진은 분석할 재료가 없다 — embeddings/run이 먼저다.
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            // when & then
            assertThatThrownBy { aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL) }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.NO_EMBEDDED_PHOTOS)
        }

        @Test
        fun `일부만 임베딩된 갤러리는 거절한다`() {
            // 부분 집합으로 시작하면 나중에 온 사진은 이번 잡에서 빠진다 — 전부 끝난 뒤에만 받는다.
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 2)
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy { aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL) }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.EMBEDDING_NOT_COMPLETE)
        }

        @Test
        fun `URL만 발급된 PENDING 사진은 완료 판정에서 뺀다`() {
            // 임베더도 PENDING은 대상이 아니다 — 영원히 안 올라올 수 있는 사진이 분석을 막으면 안 된다.
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 2)
            photoFixture.대기중_사진(fixture.galleryId, count = 1)

            // when
            val response = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL)

            // then
            assertThat(response.status).isEqualTo(AiJobStatus.PENDING)
        }

        @Test
        fun `진행 중인 잡이 있으면 새 잡을 만들지 않는다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val first = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL)

            // when & then
            assertThatThrownBy { aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL) }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)

            assertThat(aiAnalysisJobRepository.count()).isEqualTo(1L)
            assertThat(aiAnalysisJobRepository.findById(first.jobId).orElseThrow().status).isEqualTo(AiJobStatus.PENDING)
        }

        @Test
        fun `앞선 잡이 끝났으면 다시 요청할 수 있다`() {
            // 분석 배치가 DONE으로 닫은 뒤(사진을 더 올렸거나 모델이 바뀌었을 때)는 새 잡이 쌓인다.
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val first = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL)
            finishByBatch(first.jobId)

            // when
            val second = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL)

            // then
            assertThat(second.jobId).isNotEqualTo(first.jobId)
            assertThat(aiAnalysisJobRepository.count()).isEqualTo(2L)
        }

        @Test
        fun `담당 작가가 아니면 거절한다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy { aiAnalysisService.request(fixture.galleryId, fixture.member.id!!, AiAnalysisMode.FULL) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Nested
    @DisplayName("이름 붙이기(NAMING)를 요청할 때")
    inner class RequestNaming {

        @Test
        fun `FULL이 DONE이면 NAMING 잡을 만든다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val full = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL)
            finishByBatch(full.jobId)

            // when
            val naming = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.NAMING)

            // then
            assertSoftly { softly ->
                softly.assertThat(naming.mode).isEqualTo(AiAnalysisMode.NAMING)
                softly.assertThat(naming.status).isEqualTo(AiJobStatus.PENDING)
                softly.assertThat(naming.jobId).isNotEqualTo(full.jobId)
            }
        }

        @Test
        fun `FULL이 끝난 적 없으면 거절한다`() {
            // 이름 붙이기는 full이 남긴 임베딩 그룹 위에서 돈다 — 재료가 없으면 받지 않는다.
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy {
                aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.NAMING)
            }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.FULL_ANALYSIS_NOT_DONE)
        }

        @Test
        fun `진행 중인 잡이 있으면 NAMING도 거절한다`() {
            // 모드와 무관하게 갤러리당 살아 있는 잡은 하나다 — 부분 유니크가 최종 방어선이다.
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val full = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL)
            finishByBatch(full.jobId)
            aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.NAMING)

            // when & then
            assertThatThrownBy {
                aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.NAMING)
            }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)
        }
    }

    @Nested
    @DisplayName("상태를 볼 때")
    inner class Latest {

        @Test
        fun `가장 최근 잡을 돌려준다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val first = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL)
            finishByBatch(first.jobId)
            val second = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL)

            // when
            val latest = aiAnalysisService.latest(fixture.galleryId, fixture.photographer.id!!)

            // then
            assertThat(latest.jobId).isEqualTo(second.jobId)
        }

        @Test
        fun `배치가 바꾼 상태와 오류를 그대로 보여 준다`() {
            // 이 서버는 PENDING만 쓴다 — 이후 전이는 배치가 DB에 직접 하므로 그 값을 읽어야 한다.
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val job = aiAnalysisService.request(fixture.galleryId, fixture.photographer.id!!, AiAnalysisMode.FULL)
            jdbcTemplate.update(
                "UPDATE ai_analysis_jobs SET status = 'FAILED', started_at = now(), finished_at = now(), error = ? WHERE id = ?",
                "CUDA out of memory",
                job.jobId,
            )

            // when
            val latest = aiAnalysisService.latest(fixture.galleryId, fixture.photographer.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(latest.status).isEqualTo(AiJobStatus.FAILED)
                softly.assertThat(latest.error).isEqualTo("CUDA out of memory")
                softly.assertThat(latest.finishedAt).isNotNull()
            }
        }

        @Test
        fun `요청한 적이 없으면 404다`() {
            // when & then
            assertThatThrownBy { aiAnalysisService.latest(fixture.galleryId, fixture.photographer.id!!) }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.ANALYSIS_JOB_NOT_FOUND)
        }
    }

    /** 분석 배치가 하는 일을 흉내 낸다 — 이 서버에는 잡을 닫는 경로가 없다. */
    private fun finishByBatch(jobId: Long) {
        jdbcTemplate.update(
            "UPDATE ai_analysis_jobs SET status = 'DONE', started_at = now(), finished_at = now() WHERE id = ?",
            jobId,
        )
    }
}
