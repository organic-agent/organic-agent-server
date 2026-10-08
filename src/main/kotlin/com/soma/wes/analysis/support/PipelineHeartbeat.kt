package com.soma.wes.analysis.support

import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.repository.PhotoPipelineRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 업로드·분석 파이프라인의 하트비트. 1분마다 두 가지를 한다.
 *
 * 1. 분석 스윕이 멈췄는지 본다([AnalysisSweepWatch]) — **DB를 읽기 전에** 한다. DB가 응답을 멈추면 아래 집계가 막히는데, 그때가
 *    바로 알려야 할 때다(#275).
 * 2. 대기량 한 줄을 남긴다 — 평소 상태를 보는 창이고, 이 줄이 끊기면 앱 자체가 멈춘 것이다. 일이 없어도 찍는다(스윕 로그는 변화가
 *    있을 때만 남아 기준이 못 된다). 줄 끊김 알림은 바깥 감시(Grafana, organic-agent-infra#82)의 일이다. 필드 이름은 대시보드가 그대로 읽는다.
 *
 * 스케줄러 스레드는 3개다(`application-api-runtime.yml`) — 분석 스윕과 매시 정리 작업이 함께 막혀도 이 작업은 돈다.
 */
@Component
class PipelineHeartbeat(
    private val sweepWatch: AnalysisSweepWatch,
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.analysis.heartbeat-interval:PT1M}")
    fun beat() {
        LogContext.sweep {
            sweepWatch.check()
            log.info(
                "event=sweep.heartbeat active_jobs={} embed_inflight={} pending={} unscored={}",
                analysisJobRepository.countByStatusIn(AnalysisStatus.ACTIVE),
                photoPipelineRepository.countInFlightEmbedBatches(),
                photoPipelineRepository.countPending(),
                photoPipelineRepository.countScoreBacklog(),
            )
        }
    }
}
