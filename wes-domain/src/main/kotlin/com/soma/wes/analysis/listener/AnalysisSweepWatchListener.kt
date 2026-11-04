package com.soma.wes.analysis.listener

import com.soma.wes.analysis.support.AnalysisSweepWatch
import com.soma.wes.global.cluster.SchedulerLeadershipAcquiredEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 방금 리더가 됐다(#313) — 리더가 아닌 동안 스윕을 돌리지 않아 [AnalysisSweepWatch]의 마지막 완료 시각이 낡았다. 그대로 두면 첫 하트비트가
 * 멈춤을 잘못 알린다. 리더가 된 시각에서 다시 잰다.
 */
@Component
class AnalysisSweepWatchListener(
    private val sweepWatch: AnalysisSweepWatch,
) {

    @EventListener(SchedulerLeadershipAcquiredEvent::class)
    fun restartOnLeadership() {
        sweepWatch.completed()
    }
}
