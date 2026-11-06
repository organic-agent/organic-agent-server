package com.soma.wes.analysis.service

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.support.AnalysisSweepWatch
import com.soma.wes.photo.repository.PhotoPipelineRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 업로드·분석 파이프라인의 하트비트 한 번. 두 가지를 한다.
 *
 * 1. 분석 스윕이 멈췄는지 본다([AnalysisSweepWatch]) — **DB를 읽기 전에** 한다. DB가 응답을 멈추면 아래 집계가 막히는데, 그때가
 *    바로 알려야 할 때다(#275).
 * 2. 대기량 한 줄을 남긴다 — 평소 상태를 보는 창이고, 이 줄이 끊기면 앱 자체가 멈춘 것이다. 일이 없어도 찍는다(스윕 로그는 변화가
 *    있을 때만 남아 기준이 못 된다). 줄 끊김 알림은 바깥 감시(Grafana, organic-agent-infra#82)의 일이다. 필드 이름은 대시보드가 그대로 읽는다.
 */
@Service
class PipelineHeartbeatService(
    private val sweepWatch: AnalysisSweepWatch,
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 트랜잭션 없이 돈다 — [AnalysisSweepWatch.check]가 멈춤을 디스코드로 알리는 동안 커넥션을 물지 않게(TX-05).
     * 네 개의 수는 로그 한 줄의 근사치라 같은 스냅샷일 필요가 없다.
     */
    fun beat() {
        sweepWatch.check()
        val dispatchedSince = ZonedDateTime.now(clock).minus(properties.embedRedispatchAfter)
        log.info(
            "event=sweep.heartbeat active_jobs={} embed_inflight={} pending={} unscored={}",
            analysisJobRepository.countByStatusIn(AnalysisStatus.ACTIVE),
            photoPipelineRepository.countInFlightEmbedBatches(dispatchedSince),
            photoPipelineRepository.countPending(),
            photoPipelineRepository.countScoreBacklog(dispatchedSince),
        )
    }
}
