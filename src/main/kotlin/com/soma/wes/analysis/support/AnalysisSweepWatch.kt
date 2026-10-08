package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.OpsAlertDto
import com.soma.wes.analysis.service.port.OpsAlertSender
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 분석 스윕이 멈췄는지 지켜본다(#275). 스윕은 회차가 끝날 때마다 [completed]로 시각을 남기고, 하트비트가 1분마다 [check]로
 * 그 시각이 [AnalysisProperties.sweepStallAfter]보다 오래됐는지 본다. 멈추면 한 번, 다시 돌면 한 번 운영 알림을 보낸다.
 *
 * DB를 읽지 않는다 — 2026-10-08 사고처럼 DB가 응답을 멈춘 때가 바로 알려야 할 때다. 시각은 메모리에만 있어 앱이 죽으면 함께
 * 사라진다. 앱 자체가 멈춘 것은 바깥 감시(하트비트 줄이 끊기면 알리는 Grafana 규칙, organic-agent-infra#82)가 맡는다.
 * 알림이 실패해도 삼키고 로그만 남긴다 — 감시가 하트비트를 막으면 안 된다.
 */
@Component
class AnalysisSweepWatch(
    private val opsAlertSender: OpsAlertSender,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 마지막으로 끝난 회차의 시각. 기동 시각에서 시작한다 — 첫 회차부터 멈춰도 알 수 있게. */
    @Volatile
    private var lastCompletedAt: Instant = clock.instant()

    /** 멈춤을 알린 뒤 아직 회복을 알리지 않았으면 그때의 마지막 완료 시각. [check]만 쓴다(하트비트 스레드 하나). */
    private var stalledSince: Instant? = null

    /** 스윕 회차 하나가 끝났다. [at]은 테스트가 오래전에 끝난 것처럼 꾸밀 때만 넘긴다. */
    fun completed(at: Instant = clock.instant()) {
        lastCompletedAt = at
    }

    /** 멈춤·회복이 바뀐 때만 알린다. 같은 상태가 이어지면 아무것도 하지 않는다. */
    fun check() {
        val last = lastCompletedAt
        val stalledFor = Duration.between(last, clock.instant())
        val since = stalledSince
        when {
            since == null && stalledFor > properties.sweepStallAfter -> {
                stalledSince = last
                log.warn("event=sweep.stalled last_completed={} stalled_seconds={}", at(last), stalledFor.seconds)
                send(
                    OpsAlertDto(
                        title = "분석 스윕 멈춤 · ${stalledFor.toMinutes()}분째",
                        lines = listOf(
                            "마지막으로 끝난 회차: ${at(last)}",
                            "업로드된 사진의 AI 분석이 진행되지 않고 있어요. DB 응답과 앱 스레드 덤프(scheduling-*)를 확인하세요.",
                        ),
                    ),
                )
            }
            since != null && stalledFor <= properties.sweepStallAfter -> {
                stalledSince = null
                val stoppedFor = Duration.between(since, last)
                log.info("event=sweep.recovered stopped_seconds={}", stoppedFor.seconds)
                send(OpsAlertDto(title = "분석 스윕 회복", lines = listOf("약 ${stoppedFor.toMinutes()}분 멈췄다가 다시 돌아요.")))
            }
        }
    }

    private fun send(alert: OpsAlertDto) {
        if (!opsAlertSender.isAvailable()) {
            log.info("운영 알림 채널이 설정되지 않아 '{}'을 로그로만 남긴다", alert.title)
            return
        }
        try {
            opsAlertSender.send(alert)
        } catch (e: RuntimeException) {
            log.error("분석 스윕 감시 알림 실패 — 하트비트는 계속한다", e)
        }
    }

    private fun at(instant: Instant): ZonedDateTime = instant.atZone(clock.zone)
}
