package com.soma.wes.analysis.service

import com.soma.wes.analysis.domain.AnalysisMode
import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
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
    @DisplayName("분석(FULL)을 요청할 때")
    inner class Request {

        @Test
        fun `업로드가 끝난 사진이 있으면 잡을 만들고 커밋 뒤 첫 단계를 부른다`() {
            // given — 임베딩 전이라도 받는다. EMBED가 같은 잡의 첫 단계다.
            photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            // when
            val response = analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.galleryId).isEqualTo(fixture.galleryId)
                softly.assertThat(response.mode).isEqualTo(AnalysisMode.FULL)
                softly.assertThat(response.status).isEqualTo(AnalysisStatus.PENDING)
                softly.assertThat(response.stage).isEqualTo(AnalysisStage.EMBED)
                softly.assertThat(response.error).isNull()
            }
            assertThat(stageInvoker.callsOf(response.jobId).map { it.stage }).containsExactly(AnalysisStage.EMBED)
        }

        @Test
        fun `URL만 발급된 사진뿐이면 거절한다`() {
            // PENDING은 S3 객체가 없을 수 있어 임베더도 대상이 아니다 — 빈 잡은 곧바로 완료로 닫혀 "끝났다"처럼 보인다.
            // given
            photoFixture.대기중_사진(fixture.galleryId, count = 2)

            // when & then
            assertThatThrownBy { analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL) }
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
            assertThatThrownBy { analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL) }
                .isInstanceOf(AnalysisException::class.java)
                .extracting("errorCode")
                .isEqualTo(AnalysisErrorCode.STAGE_NOT_CONFIGURED)
            assertThat(analysisJobRepository.count()).isZero()
        }

        @Test
        fun `진행 중인 잡이 있으면 새 잡을 만들지 않는다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL)

            // when & then
            assertThatThrownBy { analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL) }
                .isInstanceOf(AnalysisException::class.java)
                .extracting("errorCode")
                .isEqualTo(AnalysisErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)
            assertThat(analysisJobRepository.count()).isEqualTo(1L)
            assertThat(analysisJobRepository.findById(first.jobId).orElseThrow().status).isEqualTo(AnalysisStatus.PENDING)
        }

        @Test
        fun `앞선 잡이 끝났으면 다시 요청할 수 있다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL)
            finishByLambda(first.jobId)

            // when
            val second = analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL, force = true)

            // then
            assertThat(second.jobId).isNotEqualTo(first.jobId)
            assertThat(analysisJobRepository.count()).isEqualTo(2L)
            assertThat(stageInvoker.callsOf(second.jobId).single().force).isTrue()
        }

        @Test
        fun `담당 작가가 아니면 거절한다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy { analysisService.request(fixture.galleryId, fixture.member.id!!, AnalysisMode.FULL) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Nested
    @DisplayName("이름 붙이기(NAMING)를 요청할 때")
    inner class RequestNaming {

        @Test
        fun `FULL이 DONE이면 CATEGORIZE 단계 하나짜리 잡을 만든다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val full = analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL)
            finishByLambda(full.jobId)

            // when
            val naming = analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.NAMING)

            // then
            assertSoftly { softly ->
                softly.assertThat(naming.mode).isEqualTo(AnalysisMode.NAMING)
                softly.assertThat(naming.stage).isEqualTo(AnalysisStage.CATEGORIZE)
                softly.assertThat(naming.jobId).isNotEqualTo(full.jobId)
            }
            assertThat(stageInvoker.callsOf(naming.jobId).map { it.stage }).containsExactly(AnalysisStage.CATEGORIZE)
        }

        @Test
        fun `FULL이 끝난 적 없으면 거절한다`() {
            // 이름 붙이기는 FULL이 남긴 임베딩 그룹 위에서 돈다 — 재료가 없으면 받지 않는다.
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy { analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.NAMING) }
                .isInstanceOf(AnalysisException::class.java)
                .extracting("errorCode")
                .isEqualTo(AnalysisErrorCode.FULL_ANALYSIS_NOT_DONE)
        }
    }

    @Nested
    @DisplayName("상태를 볼 때")
    inner class Latest {

        @Test
        fun `가장 최근 잡을 돌려준다`() {
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL)
            finishByLambda(first.jobId)
            val second = analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL)

            // when
            val latest = analysisService.latest(fixture.galleryId, fixture.photographer.id!!)

            // then
            assertThat(latest.jobId).isEqualTo(second.jobId)
        }

        @Test
        fun `Lambda가 바꾼 상태와 오류를 그대로 보여 준다`() {
            // 옛 계약의 Lambda는 status·error를 직접 쓴다 — 그 값을 읽어야 한다.
            // given
            photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
            val job = analysisService.request(fixture.galleryId, fixture.photographer.id!!, AnalysisMode.FULL)
            jdbcTemplate.update(
                "UPDATE ai_analysis_jobs SET status = 'FAILED', started_at = now(), finished_at = now(), error = ? WHERE id = ?",
                "CUDA out of memory",
                job.jobId,
            )

            // when
            val latest = analysisService.latest(fixture.galleryId, fixture.photographer.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(latest.status).isEqualTo(AnalysisStatus.FAILED)
                softly.assertThat(latest.error).isEqualTo("CUDA out of memory")
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

    /** 체인 끝의 Lambda(categorize)가 잡을 닫는 것을 흉내 낸다. */
    private fun finishByLambda(jobId: Long) {
        jdbcTemplate.update(
            "UPDATE ai_analysis_jobs SET status = 'DONE', started_at = now(), finished_at = now() WHERE id = ?",
            jobId,
        )
    }
}
