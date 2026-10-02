package com.soma.wes.analysis.support

import com.soma.wes.analysis.service.AnalysisService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.CapturedLogs
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** 하트비트 한 줄의 계약 — 운영 알림("5분 동안 하트비트가 없다")과 대시보드가 이 이벤트 이름과 필드를 그대로 읽는다. */
@IntegrationTest
class PipelineHeartbeatTest @Autowired constructor(
    private val heartbeat: PipelineHeartbeat,
    private val embedStep: EmbedStep,
    private val analysisService: AnalysisService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val aiTaskSender: FakeAiTaskSender,
) {

    @BeforeEach
    fun setUp() {
        aiTaskSender.reset()
    }

    @Test
    fun `일이 없어도 한 줄을 남긴다`() {
        // when
        val beat = CapturedLogs(PipelineHeartbeat::class).use { logs ->
            heartbeat.beat()
            logs.eventsOf("sweep.heartbeat").single()
        }

        // then
        assertSoftly { softly ->
            softly.assertThat(beat.formattedMessage)
                .isEqualTo("event=sweep.heartbeat active_jobs=0 embed_inflight=0 pending=0 unscored=0")
            softly.assertThat(beat.mdcPropertyMap[LogContext.TRACE_ID]).startsWith(LogContext.SWEEP_TRACE_PREFIX)
        }
    }

    @Test
    fun `돌고 있는 잡과 떠 있는 임베더 배치와 대기 사진 수를 찍는다`() {
        // given — 올라온 2장(임베더에 한 배치로 나감), 아직 올라오는 중인 1장, 잡 하나
        val gallery = galleryFixture.멤버와_열린_갤러리()
        photoFixture.업로드된_사진(gallery.galleryId, count = 2)
        photoFixture.대기중_사진(gallery.galleryId, count = 1)
        analysisService.request(gallery.galleryId, gallery.photographer.requiredId)
        embedStep.advance()

        // when
        val beat = CapturedLogs(PipelineHeartbeat::class).use { logs ->
            heartbeat.beat()
            logs.eventsOf("sweep.heartbeat").single()
        }

        // then
        assertThat(beat.formattedMessage)
            .isEqualTo("event=sweep.heartbeat active_jobs=1 embed_inflight=1 pending=1 unscored=2")
    }
}
