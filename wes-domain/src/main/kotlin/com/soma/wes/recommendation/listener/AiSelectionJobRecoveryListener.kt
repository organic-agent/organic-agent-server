package com.soma.wes.recommendation.listener

import com.soma.wes.recommendation.service.AiSelectionJobRecoveryService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** 앱이 뜨면 한 번 [AiSelectionJobRecoveryService.recover]를 부른다 — 리더 여부와 상관없이 첫 스윕(30초)을 기다리지 않는다. */
@Component
class AiSelectionJobRecoveryListener(
    private val recoveryService: AiSelectionJobRecoveryService,
) {

    @EventListener(ApplicationReadyEvent::class)
    fun recoverOnStartup() {
        recoveryService.recover()
    }
}
