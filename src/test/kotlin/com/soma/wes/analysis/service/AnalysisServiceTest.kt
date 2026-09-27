package com.soma.wes.analysis.service

import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.FakeStageInvoker
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
class AnalysisServiceTest @Autowired constructor(
    private val analysisService: AnalysisService,
    private val analysisJobRepository: AnalysisJobRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val stageInvoker: FakeStageInvoker,
    private val jdbcTemplate: JdbcTemplate,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        stageInvoker.reset()
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("분석을 요청할 때")
    inner class Request {

        @Test
        fun `업로드가 끝난 사진이 있으면 ANALYZING 잡을 만들고 진행을 돌려준다`() {
            // given — 임베딩 전이라도 받는다. 배정은 스윕이 한다.
            photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            // when
            val response = analysisService.request(fixture.galleryId, fixture.photographer.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.galleryId).isEqualTo(fixture.galleryId)
                softly.assertThat(response.status).isEqualTo(AnalysisStatus.ANALYZING)
                softly.assertThat(response.progress.expected).isEqualTo(2)
                softly.assertThat(response.progress.embedded).isZero()
                softly.assertThat(response.error).isNull()
                softly.assertThat(response.finishedAt).isNull()
            }
            // 요청 자체는 Lambda 를 부르지 않는다 — 점수가 없으니 categorize 도, 벡터가 없으니 score 폴백도 나갈 것이 없다.
            assertThat(stageInvoker.categorizeCalls).isEmpty()
        }

        @Test
        fun `이미 점수가 다 찬 갤러리는 커밋 직후 categorize 가 나간다`() {
            // given
            val photos = photoFixture.임베딩된_사진(fixture.galleryId, count = 2)
            photos.forEach { photoFixture.점수_적재(it) }

            // when
            val response = analysisService.request(fixture.galleryId, fixture.photographer.id!!)

            // then — afterCommit 의 한 걸음이 ANALYZING→CATEGORIZING 을 지났다
            assertThat(analysisJobRepository.findById(response.jobId).orElseThrow().status).isEqualTo(AnalysisStatus.CATEGORIZING)
            assertThat(stageInvoker.categorizeCalls.map { it.jobId }).containsExactly(response.jobId)
        }

        @Test
        fun `URL만 발급된 사진뿐이면 거절한다`() {
            // PENDING은 S3 객체가 없을 수 있어 임베더도 대상이 아니다 — 빈 잡은 곧바로 완료로 닫혀 "끝났다"처럼 보인다.
            // given
            photoFixture.대기중_사진(fixture.galleryId, count = 2)

            // when & then
            assertThatThrownBy { analysisService.request(fixture.galleryId, fixture.photographer.id!!) }
                .isInstanceOf(AnalysisException::class.java)
                .extracting("errorCode")
                .isEqualTo(AnalysisErrorCode.NO_PHOTOS_TO_ANALYZE)
        }

        @Test
        fun `실패로 표시된 사진만 있으면 거절한다`() {
            // given
            val photos = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            photoFixture.분석_실패(photos[0])

            // when & then
            assertThatThrownBy { analysisService.request(fixture.galleryId, fixture.photographer.id!!) }
                .isInstanceOf(AnalysisException::class.java)
                .extracting("errorCode")
                .isEqualTo(AnalysisErrorCode.NO_PHOTOS_TO_ANALYZE)
        }

        @Test
        fun `실행기가 설정되지 않았으면 잡을 만들지 않는다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            stageInvoker.available = false

            // when & then
            assertThatThrownBy { analysisService.request(fixture.galleryId, fixture.photographer.id!!) }
                .isInstanceOf(AnalysisException::class.java)
                .extracting("errorCode")
                .isEqualTo(AnalysisErrorCode.STAGE_NOT_CONFIGURED)
            assertThat(analysisJobRepository.count()).isZero()
        }

        @Test
        fun `진행 중인 잡이 있으면 새 잡을 만들지 않는다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.id!!)

            // when & then
            assertThatThrownBy { analysisService.request(fixture.galleryId, fixture.photographer.id!!) }
                .isInstanceOf(AnalysisException::class.java)
                .extracting("errorCode")
                .isEqualTo(AnalysisErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)
            assertThat(analysisJobRepository.count()).isEqualTo(1L)
            assertThat(analysisJobRepository.findById(first.jobId).orElseThrow().status).isEqualTo(AnalysisStatus.ANALYZING)
        }

        @Test
        fun `앞선 잡이 끝났으면 다시 요청할 수 있다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.id!!)
            finish(first.jobId)

            // when
            val second = analysisService.request(fixture.galleryId, fixture.photographer.id!!)

            // then
            assertThat(second.jobId).isNotEqualTo(first.jobId)
            assertThat(analysisJobRepository.count()).isEqualTo(2L)
        }

        @Test
        fun `담당 작가가 아니면 거절한다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy { analysisService.request(fixture.galleryId, fixture.member.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Nested
    @DisplayName("상태를 볼 때")
    inner class Latest {

        @Test
        fun `가장 최근 잡과 지금 진행을 돌려준다`() {
            // given
            val photos = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.id!!)
            finish(first.jobId)
            val second = analysisService.request(fixture.galleryId, fixture.photographer.id!!)
            photoFixture.벡터_적재(photos[0], FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { it[0] = 1f })

            // when
            val latest = analysisService.latest(fixture.galleryId, fixture.photographer.id!!)

            // then — 진행은 잡이 아니라 지금 DB 의 분석 행에서 온다
            assertSoftly { softly ->
                softly.assertThat(latest.jobId).isEqualTo(second.jobId)
                softly.assertThat(latest.progress.expected).isEqualTo(2)
                softly.assertThat(latest.progress.embedded).isEqualTo(1)
                softly.assertThat(latest.progress.scored).isZero()
            }
        }

        @Test
        fun `FAILED 잡의 오류를 그대로 보여 준다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val job = analysisService.request(fixture.galleryId, fixture.photographer.id!!)
            jdbcTemplate.update(
                "UPDATE analysis_jobs SET status = 'FAILED', finished_at = now(), error = ? WHERE id = ?",
                "bedrock timeout",
                job.jobId,
            )

            // when
            val latest = analysisService.latest(fixture.galleryId, fixture.photographer.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(latest.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(latest.error).isEqualTo("bedrock timeout")
                softly.assertThat(latest.finishedAt).isNotNull()
            }
        }

        @Test
        fun `요청한 적이 없으면 404다`() {
            // when & then
            assertThatThrownBy { analysisService.latest(fixture.galleryId, fixture.photographer.id!!) }
                .isInstanceOf(AnalysisException::class.java)
                .extracting("errorCode")
                .isEqualTo(AnalysisErrorCode.ANALYSIS_JOB_NOT_FOUND)
        }
    }

    private fun finish(jobId: Long) {
        jdbcTemplate.update("UPDATE analysis_jobs SET status = 'DONE', finished_at = now() WHERE id = ?", jobId)
    }
}
