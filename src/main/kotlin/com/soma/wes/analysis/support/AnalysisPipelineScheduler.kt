package com.soma.wes.analysis.support

import com.soma.wes.analysis.service.AnalysisPipelineService
import com.soma.wes.global.logging.LogContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 분석 파이프라인의 박동. 5초마다 [AnalysisPipelineService.advance]를 부를 뿐 판단은 하지 않는다.
 * `initialDelay`가 없어 첫 회차는 스케줄러가 켜지자마자 돌고, 상태가 전부 DB에 있어 그 한 번이 곧 기동 복구다.
 * 회차마다 traceId 하나(`sweep-…`)를 달아, 한 회차가 남긴 줄을 한 번에 모을 수 있게 한다.
 * 회차가 끝나면 [AnalysisSweepWatch]에 알린다 — 하트비트가 그 시각으로 스윕이 멈췄는지 본다.
 */
@Component
class AnalysisPipelineScheduler(
    private val pipeline: AnalysisPipelineService,
    private val sweepWatch: AnalysisSweepWatch,
) {

    @Scheduled(fixedDelayString = "\${app.analysis.sweep-interval:PT5S}")
    fun advance() {
        LogContext.sweep { pipeline.advance() }
        sweepWatch.completed()
    }
}
