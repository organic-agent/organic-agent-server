package com.soma.wes.analysis.support

import com.soma.wes.analysis.service.AnalysisPipelineService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 분석 파이프라인의 박동. 5초마다 [AnalysisPipelineService.advance]를 부를 뿐 판단은 하지 않는다.
 * `initialDelay`가 없어 첫 회차는 스케줄러가 켜지자마자 돌고, 상태가 전부 DB에 있어 그 한 번이 곧 기동 복구다.
 */
@Component
class AnalysisPipelineScheduler(
    private val pipeline: AnalysisPipelineService,
) {

    @Scheduled(fixedDelayString = "\${app.analysis.sweep-interval:PT5S}")
    fun advance() {
        pipeline.advance()
    }
}
