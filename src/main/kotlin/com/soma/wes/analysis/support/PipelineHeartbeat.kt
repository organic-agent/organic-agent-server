package com.soma.wes.analysis.support

import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.repository.PhotoPipelineRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 업로드·분석 파이프라인의 하트비트. 1분마다 대기량 한 줄을 남긴다 — 평소 상태를 보는 창이면서, 이 줄이 끊기는 것이
 * "스케줄러가 멈췄다"는 운영 알림의 기준이다. 일이 없어도 찍는다(스윕 로그는 변화가 있을 때만 남아 기준이 못 된다).
 *
 * 다른 예약 작업과 같은 스케줄러 스레드를 쓰므로, 한 작업이 스레드를 오래 붙잡으면 이 줄도 같이 늦어진다 — 그 지연이 곧 신호다.
 * 필드 이름은 알림 규칙과 대시보드가 그대로 읽는다.
 */
@Component
class PipelineHeartbeat(
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.analysis.heartbeat-interval:PT1M}")
    fun beat() {
        LogContext.sweep {
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
