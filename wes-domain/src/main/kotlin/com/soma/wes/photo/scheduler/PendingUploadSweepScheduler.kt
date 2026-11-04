package com.soma.wes.photo.scheduler

import com.soma.wes.global.cluster.SchedulerLeadership
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.service.PendingUploadSweepService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 5초마다 [PendingUploadSweepService.sweep]을 부른다 — 완료 통보 없이 남은 PENDING 사진을 마무리한다. */
@Component
class PendingUploadSweepScheduler(
    private val sweepService: PendingUploadSweepService,
    private val leadership: SchedulerLeadership,
) {

    @Scheduled(fixedDelayString = "\${app.storage.pending-sweep-interval:PT5S}")
    fun sweep() {
        if (!leadership.isLeader()) return
        LogContext.sweep { sweepService.sweep() }
    }
}
