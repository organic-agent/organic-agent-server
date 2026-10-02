package com.soma.wes.analysis.support

import com.soma.wes.analysis.service.AnalysisService
import com.soma.wes.folder.support.AiFolderMaterializer
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.CapturedLogs
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired

/**
 * 스케줄러 회차가 남기는 로그의 계약 — 한 회차의 줄은 `sweep-…` traceId 하나로 묶이고, 잡을 다루는 줄에는 갤러리·잡 번호가 실린다.
 * 운영 알림과 런북이 이 키와 이벤트 이름으로 로그를 거른다. 잡의 상태 전이 자체는 `AnalysisPipelineServiceTest`가 본다.
 */
@IntegrationTest
class AnalysisPipelineSchedulerTest @Autowired constructor(
    private val scheduler: AnalysisPipelineScheduler,
    private val analysisService: AnalysisService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val aiTaskSender: FakeAiTaskSender,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        aiTaskSender.reset()
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Test
    fun `잡 전이 줄에는 그 회차의 traceId 와 갤러리와 잡 번호가 실리고 회차가 끝나면 비워진다`() {
        // given — 점수까지 찬 사진과 잡
        photoFixture.임베딩된_사진(fixture.galleryId, count = 2).forEach { photoFixture.점수_적재(it) }
        val jobId = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId

        // when
        val transitions = CapturedLogs(CategorizeStep::class).use { logs ->
            scheduler.advance()
            logs.eventsOf("job.transition")
        }

        // then
        val transition = transitions.single()
        assertSoftly { softly ->
            softly.assertThat(transition.formattedMessage).contains("from=ANALYZING to=CATEGORIZING")
            softly.assertThat(transition.mdcPropertyMap[LogContext.TRACE_ID]).startsWith(LogContext.SWEEP_TRACE_PREFIX)
            softly.assertThat(transition.mdcPropertyMap[LogContext.GALLERY_ID]).isEqualTo(fixture.galleryId.toString())
            softly.assertThat(transition.mdcPropertyMap[LogContext.JOB_ID]).isEqualTo(jobId.toString())
            softly.assertThat(MDC.getCopyOfContextMap().orEmpty()).isEmpty()
        }
    }

    @Test
    fun `회차마다 traceId 가 다르다`() {
        // given
        photoFixture.업로드된_사진(fixture.galleryId, count = 1)
        val other = galleryFixture.멤버와_열린_갤러리().galleryId
        CapturedLogs(EmbedStep::class).use { logs ->
            // when — 첫 회차가 첫 갤러리를 보내고, 다음 회차가 새로 올라온 갤러리를 보낸다
            scheduler.advance()
            photoFixture.업로드된_사진(other, count = 1)
            scheduler.advance()

            // then
            val dispatches = logs.eventsOf("embed.dispatch")
            assertSoftly { softly ->
                softly.assertThat(dispatches.map { it.mdcPropertyMap[LogContext.GALLERY_ID] })
                    .containsExactly(fixture.galleryId.toString(), other.toString())
                softly.assertThat(dispatches.map { it.mdcPropertyMap[LogContext.TRACE_ID] }.distinct()).hasSize(2)
            }
        }
    }

    @Test
    fun `폴더가 만들어지면 물질화 줄과 완료 전이 줄이 같은 잡 번호로 남는다`() {
        // given — categorize 결과까지 온 잡
        val photos = photoFixture.임베딩된_사진(fixture.galleryId, count = 3).onEach { photoFixture.점수_적재(it) }
        val jobId = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId
        scheduler.advance()
        photos.forEach { photoFixture.백분위_적재(it, embedGroupId = 1) }
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")

        // when
        val (materialized, closed) = CapturedLogs(AiFolderMaterializer::class).use { folderLogs ->
            CapturedLogs(AnalysisJobCloser::class).use { closerLogs ->
                scheduler.advance()
                folderLogs.eventsOf("folder.materialized").single() to closerLogs.eventsOf("job.transition").single()
            }
        }

        // then
        assertSoftly { softly ->
            softly.assertThat(materialized.formattedMessage)
                .contains("gallery=${fixture.galleryId} job=$jobId concepts=1 details=1 assigned=3 elapsedMs=")
            softly.assertThat(closed.formattedMessage).contains("from=CATEGORIZING to=DONE folders=1 details=1 assigned=3")
            softly.assertThat(materialized.mdcPropertyMap[LogContext.JOB_ID]).isEqualTo(jobId.toString())
            softly.assertThat(closed.mdcPropertyMap[LogContext.JOB_ID]).isEqualTo(jobId.toString())
            softly.assertThat(closed.mdcPropertyMap[LogContext.TRACE_ID]).isEqualTo(materialized.mdcPropertyMap[LogContext.TRACE_ID])
        }
    }
}
