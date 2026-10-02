package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisFailureCode
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.repository.ConceptAssignmentRepository
import com.soma.wes.analysis.service.AnalysisPipelineService
import com.soma.wes.analysis.service.AnalysisService
import com.soma.wes.folder.support.AiFolderMaterializer
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
import java.time.Clock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired

/**
 * 폴더 만들기가 예상 밖 예외를 던질 때의 끝 — 횟수를 세고 상한에서 잡을 닫는다. 실제 물질화는 예외를 내도록 만들기 어려워
 * 그 자리만 던지는 대역으로 바꾼 단계를 직접 만든다. 나머지 판정(완료·기한·재전송)은 `AnalysisPipelineServiceTest`가 본다.
 */
@IntegrationTest
class FolderStepTest @Autowired constructor(
    private val pipeline: AnalysisPipelineService,
    private val analysisService: AnalysisService,
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val conceptAssignmentRepository: ConceptAssignmentRepository,
    private val jobCloser: AnalysisJobCloser,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val aiTaskSender: FakeAiTaskSender,
    private val clock: Clock,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        aiTaskSender.reset()
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Test
    fun `폴더 만들기가 예외로 실패하면 상한까지 다시 시도한 뒤 FOLDER_FAILED 로 닫는다`() {
        // given — categorize 결과까지 온 잡과, 만들 때마다 터지는 물질화
        val jobId = categorizedJob()
        val failing = mock<AiFolderMaterializer> { on { materialize(any()) } doThrow IllegalStateException("boom") }
        val step = FolderStep(
            analysisJobRepository,
            photoPipelineRepository,
            conceptAssignmentRepository,
            aiTaskSender,
            failing,
            jobCloser,
            AnalysisProperties(materializeMaxAttempts = 3),
            clock,
        )

        // when — 두 번까지는 잡을 닫지 않고 다음 회차에 맡긴다
        step.advance()
        step.advance()
        val afterTwo = job(jobId)
        step.advance()
        val afterThree = job(jobId)
        step.advance()

        // then — 전에는 5초마다 끝없이 다시 시도했다
        assertSoftly { softly ->
            softly.assertThat(afterTwo.status).isEqualTo(AnalysisStatus.CATEGORIZING)
            softly.assertThat(afterTwo.materializeAttempts).isEqualTo(2)
            softly.assertThat(afterThree.status).isEqualTo(AnalysisStatus.FAILED)
            softly.assertThat(afterThree.errorCode).isEqualTo(AnalysisFailureCode.FOLDER_FAILED)
            softly.assertThat(job(jobId).materializeAttempts).isEqualTo(3)
        }
    }

    private fun categorizedJob(): Long {
        val photos = photoFixture.임베딩된_사진(fixture.galleryId, count = 2).onEach { photoFixture.점수_적재(it) }
        val jobId = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId
        pipeline.advance()
        photos.forEach { photoFixture.백분위_적재(it, embedGroupId = 1) }
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
        assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
        return jobId
    }

    private fun job(jobId: Long): AnalysisJob = analysisJobRepository.findById(jobId).orElseThrow()
}
