package com.soma.wes.analysis.support

import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.AnalysisPipelineService
import com.soma.wes.analysis.service.AnalysisService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.FakeOpsAlertSender
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull

/**
 * 분석이 끝난 갤러리에 실패한 사진이 한 장이라도 있으면 운영 채널에 백오피스 링크와 함께 한 번 알린다. 알림은 운영 확인용이라 잡 닫기를 막지 않는다.
 */
@IntegrationTest
class FailedPhotoAlerterTest @Autowired constructor(
    private val pipeline: AnalysisPipelineService,
    private val analysisService: AnalysisService,
    private val analysisJobRepository: AnalysisJobRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val aiTaskSender: FakeAiTaskSender,
    private val opsAlertSender: FakeOpsAlertSender,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        aiTaskSender.reset()
        opsAlertSender.reset()
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    /** 점수까지 찬 사진 3장과 실패한 사진 [failed]장으로 잡 하나를 끝까지(DONE) 돌린다. */
    private fun finishJobWith(failed: Int, error: String = PhotoPipelineRepository.EMBED_ATTEMPTS_EXCEEDED): Long {
        val photos = photoFixture.임베딩된_사진(fixture.galleryId, count = 3).onEach { photoFixture.점수_적재(it) }
        photoFixture.업로드된_사진(fixture.galleryId, count = failed).forEach { photoFixture.분석_실패(it, error) }
        val jobId = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId

        pipeline.advance()
        photos.forEach { photoFixture.백분위_적재(it, embedGroupId = 1) }
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
        pipeline.advance()

        return jobId
    }

    private fun statusOf(jobId: Long) = analysisJobRepository.findByIdOrNull(jobId)?.status

    @Nested
    @DisplayName("실패 사진을 셀 때")
    inner class Threshold {

        @Test
        fun `한 장이라도 실패하면 사유별 장수와 백오피스 갤러리 링크를 담아 한 번 알린다`() {
            // when
            val jobId = finishJobWith(failed = 1)

            // then
            val alert = opsAlertSender.alerts.single()
            assertSoftly { softly ->
                softly.assertThat(statusOf(jobId)).isEqualTo(AnalysisStatus.DONE)
                softly.assertThat(alert.title).isEqualTo("분석 실패 사진 1장 · 갤러리 ${fixture.galleryId}")
                softly.assertThat(alert.lines).contains("사유: ${PhotoPipelineRepository.EMBED_ATTEMPTS_EXCEEDED} 1장")
                softly.assertThat(alert.lines)
                    .contains("백오피스에서 열기: https://admin.easyselect.kr/resources?type=GALLERY&id=${fixture.galleryId}")
            }
        }

        @Test
        fun `실패한 사진이 없으면 알리지 않는다`() {
            // when
            val jobId = finishJobWith(failed = 0)

            // then
            assertThat(statusOf(jobId)).isEqualTo(AnalysisStatus.DONE)
            assertThat(opsAlertSender.alerts).isEmpty()
        }

        @Test
        fun `다음 잡은 직전 잡이 끝난 뒤의 실패만 세어 같은 실패로 다시 알리지 않는다`() {
            // given — 첫 잡이 실패 2장으로 한 번 알렸다
            finishJobWith(failed = 2)
            opsAlertSender.reset()

            // when — 사진을 더 올려 두 번째 잡이 끝난다. 새 실패는 없다
            val secondJobId = finishJobWith(failed = 0)

            // then
            assertThat(statusOf(secondJobId)).isEqualTo(AnalysisStatus.DONE)
            assertThat(opsAlertSender.alerts).isEmpty()
        }
    }

    @Nested
    @DisplayName("알림을 보내지 못할 때")
    inner class SendFailure {

        @Test
        fun `웹훅이 실패해도 잡은 DONE 으로 닫힌다`() {
            // given
            opsAlertSender.failNext = true

            // when
            val jobId = finishJobWith(failed = 1)

            // then
            assertThat(statusOf(jobId)).isEqualTo(AnalysisStatus.DONE)
            assertThat(opsAlertSender.alerts).isEmpty()
        }

        @Test
        fun `알림 채널이 설정되지 않았으면 보내지 않고 잡은 닫힌다`() {
            // given
            opsAlertSender.available = false

            // when
            val jobId = finishJobWith(failed = 1)

            // then
            assertThat(statusOf(jobId)).isEqualTo(AnalysisStatus.DONE)
            assertThat(opsAlertSender.alerts).isEmpty()
        }
    }
}
