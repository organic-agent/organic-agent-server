package com.soma.wes.analysis.support

import com.soma.wes.analysis.service.AnalysisOrchestrator
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 살아 있는 분석 잡을 주기적으로 한 걸음씩 옮긴다 — 단계 완료 뒤 다음 단계 호출, 유실된 호출의 재시도, 정체 감지, 잡 닫기.
 * 기동 직후에도 한 번 돈다: 이전 프로세스가 죽는 바람에 멈춘 잡은 상태가 전부 DB에 있어 스윕 한 번이 곧 복구다.
 * 인스턴스가 여럿이면 같은 잡을 동시에 볼 수 있는데, 그 충돌은 낙관적 잠금이 잡고 진 쪽은 다음 스윕에서 다시 본다.
 */
@Component
class AnalysisJobSweeper(
    private val orchestrator: AnalysisOrchestrator,
) {

    @EventListener(ApplicationReadyEvent::class)
    fun sweepOnStartup() {
        orchestrator.sweep()
    }

    @Scheduled(fixedDelayString = "\${app.analysis.sweep-interval:PT30S}")
    fun sweep() {
        orchestrator.sweep()
    }
}
