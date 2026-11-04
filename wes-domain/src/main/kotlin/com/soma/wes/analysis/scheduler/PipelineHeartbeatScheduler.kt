package com.soma.wes.analysis.scheduler

import com.soma.wes.analysis.service.PipelineHeartbeatService
import com.soma.wes.global.cluster.SchedulerLeadership
import com.soma.wes.global.logging.LogContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 1분마다 [PipelineHeartbeatService.beat]를 부른다 — 스윕 멈춤 확인과 대기량 한 줄.
 *
 * 스케줄러 스레드는 3개다(`application-api-runtime.yml`) — 분석 스윕과 매시 정리 작업이 함께 막혀도 이 작업은 돈다.
 */
@Component
class PipelineHeartbeatScheduler(
    private val heartbeatService: PipelineHeartbeatService,
    private val leadership: SchedulerLeadership,
) {

    @Scheduled(fixedDelayString = "\${app.analysis.heartbeat-interval:PT1M}")
    fun beat() {
        if (!leadership.isLeader()) return
        LogContext.sweep { heartbeatService.beat() }
    }
}
