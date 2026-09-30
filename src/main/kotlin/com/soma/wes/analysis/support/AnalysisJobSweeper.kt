package com.soma.wes.analysis.support

import com.soma.wes.analysis.service.AnalysisOrchestrator
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 분석 파이프라인의 박동. 5초마다 [AnalysisOrchestrator.sweep]을 부를 뿐 판단은 하지 않는다.
 *
 * - 기동 복구: `initialDelay`가 없어 첫 회차가 스케줄러가 켜지자마자 돈다. 상태가 전부 DB에 있어 그 한 번이 곧 복구다.
 *   `ApplicationReadyEvent`에 따로 걸지 않는다 — 첫 회차와 겹쳐 두 번 돌고, 메인 스레드라 readiness를 늦춘다.
 * - 다중 인스턴스: 같은 잡·사진을 동시에 볼 수 있지만 낙관적 잠금(잡)과 `SKIP LOCKED`(사진)가 한쪽만 이기게 한다.
 */
@Component
class AnalysisJobSweeper(
    private val orchestrator: AnalysisOrchestrator,
) {

    @Scheduled(fixedDelayString = "\${app.analysis.sweep-interval:PT5S}")
    fun sweep() {
        orchestrator.sweep()
    }
}
