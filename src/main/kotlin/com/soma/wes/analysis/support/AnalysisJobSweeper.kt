package com.soma.wes.analysis.support

import com.soma.wes.analysis.service.AnalysisOrchestrator
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 5초마다 임베더 배정과 살아 있는 분석 잡의 한 걸음을 돈다 — 올라온 사진 배정, 점수 완료 관측 뒤 categorize 호출,
 * 타임아웃 재전송, 물질화·잡 닫기. 기동 직후에도 한 번 돈다: 이전 프로세스가 죽는 바람에 멈춘 잡은 상태가 전부 DB에 있어
 * 스윕 한 번이 곧 복구다. 인스턴스가 여럿이면 같은 잡을 동시에 볼 수 있는데, 그 충돌은 낙관적 잠금(잡)·`SKIP LOCKED`(사진)가 잡는다.
 */
@Component
class AnalysisJobSweeper(
    private val orchestrator: AnalysisOrchestrator,
) {

    @EventListener(ApplicationReadyEvent::class)
    fun sweepOnStartup() {
        orchestrator.sweep()
    }

    @Scheduled(fixedDelayString = "\${app.analysis.sweep-interval:PT5S}")
    fun sweep() {
        orchestrator.sweep()
    }
}
