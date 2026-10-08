package com.soma.wes.analysis.support

import com.soma.wes.analysis.service.AnalysisService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.CapturedLogs
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
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

/**
 * 하트비트의 두 가지 일 — 대기량 한 줄(대시보드와 바깥 감시가 이벤트 이름·필드를 그대로 읽는다)과 분석 스윕 멈춤 알림(#275).
 */
@IntegrationTest
class PipelineHeartbeatTest @Autowired constructor(
    private val heartbeat: PipelineHeartbeat,
    private val sweepWatch: AnalysisSweepWatch,
    private val embedStep: EmbedStep,
    private val analysisService: AnalysisService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val aiTaskSender: FakeAiTaskSender,
    private val opsAlertSender: FakeOpsAlertSender,
    private val postgres: PostgreSQLContainer<*>,
    private val clock: Clock,
) {

    companion object {
        /** DB가 멈춘 동안 알림이 오기까지 기다리는 시간 — 알림은 DB를 거치지 않아 바로 와야 한다. */
        private val ALERT_WAIT: Duration = Duration.ofSeconds(5)

        private const val POLL_MILLIS = 50L

        /** DB를 다시 켠 뒤 막혀 있던 하트비트가 끝나기를 기다리는 시간. */
        private const val HEARTBEAT_JOIN_MILLIS = 60_000L
    }

    @BeforeEach
    fun setUp() {
        aiTaskSender.reset()
        // 앞 테스트가 남긴 멈춤 상태를 회복으로 정리한 뒤 알림 기록을 비운다
        sweepWatch.completed()
        sweepWatch.check()
        opsAlertSender.reset()
    }

    @Nested
    @DisplayName("대기량을 남길 때")
    inner class Backlog {

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

    @Nested
    @DisplayName("분석 스윕이 멈췄는지 볼 때")
    inner class SweepStall {

        @Test
        fun `3분 안에 회차가 끝났으면 알리지 않는다`() {
            // given
            sweepWatch.completed(at = minutesAgo(2))

            // when
            heartbeat.beat()

            // then
            assertThat(opsAlertSender.alerts).isEmpty()
        }

        @Test
        fun `3분 넘게 회차가 끝나지 않으면 이어지는 하트비트에도 한 번만 알린다`() {
            // given
            sweepWatch.completed(at = minutesAgo(4))

            // when
            val stalled = CapturedLogs(AnalysisSweepWatch::class).use { logs ->
                heartbeat.beat()
                heartbeat.beat()
                logs.eventsOf("sweep.stalled")
            }

            // then
            assertSoftly { softly ->
                softly.assertThat(opsAlertSender.alerts.map { it.title }).containsExactly("분석 스윕 멈춤 · 4분째")
                softly.assertThat(stalled).hasSize(1)
            }
        }

        @Test
        fun `멈췄던 스윕이 다시 끝나면 회복을 한 번 알린다`() {
            // given — 멈춤을 이미 알렸다
            sweepWatch.completed(at = minutesAgo(4))
            heartbeat.beat()

            // when
            sweepWatch.completed()
            heartbeat.beat()
            heartbeat.beat()

            // then
            assertThat(opsAlertSender.alerts.map { it.title }).containsExactly("분석 스윕 멈춤 · 4분째", "분석 스윕 회복")
        }

        @Test
        fun `DB가 응답하지 않아도 멈춤 알림은 나간다`() {
            // given — 스윕이 멈춘 채 DB까지 응답을 멈춘다 (2026-10-08 사고)
            sweepWatch.completed(at = minutesAgo(4))
            pauseDatabase()
            val beating = try {
                // when — 하트비트는 알림 뒤 DB 집계에서 막힌다
                thread { runCatching { heartbeat.beat() } }.also { awaitAlert() }
            } finally {
                unpauseDatabase()
            }
            beating.join(HEARTBEAT_JOIN_MILLIS)

            // then
            assertThat(opsAlertSender.alerts.map { it.title }).containsExactly("분석 스윕 멈춤 · 4분째")
        }

        private fun awaitAlert() {
            val deadline = Instant.now().plus(ALERT_WAIT)
            while (opsAlertSender.alerts.isEmpty() && Instant.now().isBefore(deadline)) {
                Thread.sleep(POLL_MILLIS)
            }
        }
    }

    private fun minutesAgo(minutes: Long): Instant = clock.instant().minus(Duration.ofMinutes(minutes))

    private fun pauseDatabase() {
        postgres.dockerClient.pauseContainerCmd(postgres.containerId).exec()
    }

    private fun unpauseDatabase() {
        postgres.dockerClient.unpauseContainerCmd(postgres.containerId).exec()
    }
}
