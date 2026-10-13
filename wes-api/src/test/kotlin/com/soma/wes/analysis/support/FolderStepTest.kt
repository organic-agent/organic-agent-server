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
import com.soma.wes.global.config.AnalysisPoolRoute
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
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
    private val eventRecorder: AnalysisJobEventRecorder,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val aiTaskSender: FakeAiTaskSender,
    private val materializer: AiFolderMaterializer,
    private val clock: Clock,
    private val analysisPoolRoute: AnalysisPoolRoute,
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
            eventRecorder,
            AnalysisProperties(materializeMaxAttempts = 3),
            clock,
            analysisPoolRoute,
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

    @Test
    fun `categorize 결과가 온 잡이 여럿이면 한 줄로 기다리지 않고 동시에 폴더로 만든다`() {
        // given — 두 갤러리의 결과가 같은 순간에 왔고, 물질화는 둘이 모두 들어와야 끝난다(한 줄이면 첫째가 시간 초과)
        val other = galleryFixture.멤버와_열린_갤러리()
        val (first, firstPhotos) = categorizingJob(fixture)
        val (second, secondPhotos) = categorizingJob(other)
        categorizeByLambda(first, fixture, firstPhotos)
        categorizeByLambda(second, other, secondPhotos)
        val bothInside = CountDownLatch(2)
        val metEachOther = CopyOnWriteArrayList<Boolean>()
        val step = stepWith(
            mock {
                on { materialize(any()) } doAnswer { call ->
                    bothInside.countDown()
                    metEachOther += bothInside.await(2, TimeUnit.SECONDS)
                    materializer.materialize(call.getArgument(0))
                }
            },
        )

        // when
        try {
            step.advance()
        } finally {
            step.destroy()
        }

        // then — 전에는 다섯째 갤러리가 앞 넷의 물질화(각 1.2초)를 기다렸다(#274 R-2-2)
        assertSoftly { softly ->
            softly.assertThat(metEachOther).containsExactly(true, true)
            softly.assertThat(job(first).status).isEqualTo(AnalysisStatus.DONE)
            softly.assertThat(job(second).status).isEqualTo(AnalysisStatus.DONE)
        }
    }

    @Test
    fun `앞 호출이 아직 폴더를 만드는 중이면 겹친 호출은 건너뛴다`() {
        // given — 1초 주기와 5초 스윕이 같은 잡을 동시에 보는 상황
        val jobId = categorizedJob()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val step = stepWith(
            mock {
                on { materialize(any()) } doAnswer { call ->
                    calls.incrementAndGet()
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    materializer.materialize(call.getArgument(0))
                }
            },
        )
        val running = CompletableFuture.runAsync { step.advance() }
        entered.await(5, TimeUnit.SECONDS)

        // when
        try {
            step.advance()
            release.countDown()
            running.join()
        } finally {
            step.destroy()
        }

        // then
        assertSoftly { softly ->
            softly.assertThat(calls.get()).isEqualTo(1)
            softly.assertThat(job(jobId).status).isEqualTo(AnalysisStatus.DONE)
        }
    }

    private fun stepWith(materializer: AiFolderMaterializer) = FolderStep(
        analysisJobRepository,
        photoPipelineRepository,
        conceptAssignmentRepository,
        aiTaskSender,
        materializer,
        jobCloser,
        eventRecorder,
        AnalysisProperties(),
        clock,
        analysisPoolRoute,
    )

    private fun categorizedJob(): Long {
        val (jobId, photos) = categorizingJob(fixture)
        categorizeByLambda(jobId, fixture, photos)
        return jobId
    }

    /** categorize 를 보낸 잡(CATEGORIZING)과 그 사진. 결과는 아직 없다 — 결과가 있으면 다음 회차가 바로 폴더로 만든다. */
    private fun categorizingJob(gallery: OpenGallery): Pair<Long, List<Long>> {
        val photos = photoFixture.임베딩된_사진(gallery.galleryId, count = 2).onEach { photoFixture.점수_적재(it) }
        val jobId = analysisService.request(gallery.galleryId, gallery.photographer.requiredId).jobId
        pipeline.advance()
        assertThat(job(jobId).status).isEqualTo(AnalysisStatus.CATEGORIZING)
        return jobId to photos
    }

    /** categorize Lambda 역할 — 백분위·그룹을 채우고 잡의 배정을 남긴다. */
    private fun categorizeByLambda(jobId: Long, gallery: OpenGallery, photos: List<Long>) {
        photos.forEach { photoFixture.백분위_적재(it, embedGroupId = 1) }
        recommendationFixture.컨셉_배정(jobId, gallery.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
    }

    private fun job(jobId: Long): AnalysisJob = analysisJobRepository.findById(jobId).orElseThrow()
}
