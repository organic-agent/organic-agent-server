package com.soma.wes.recommendation.scheduler

import com.soma.wes.global.cluster.SchedulerLeadership
import com.soma.wes.recommendation.service.AiSelectionJobRecoveryService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 30초마다 [AiSelectionJobRecoveryService.recover]를 부른다 — 잊힌 PENDING과 죽은 RUNNING을 다시 줄에 세운다. */
@Component
class AiSelectionJobRecoveryScheduler(
    private val recoveryService: AiSelectionJobRecoveryService,
    private val leadership: SchedulerLeadership,
) {

    @Scheduled(fixedDelayString = "PT30S")
    fun sweepPending() {
        if (!leadership.isLeader()) return
        recoveryService.recover()
    }
}
