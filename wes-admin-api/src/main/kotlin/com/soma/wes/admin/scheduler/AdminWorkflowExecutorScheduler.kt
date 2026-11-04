package com.soma.wes.admin.scheduler

import com.soma.wes.admin.resource.config.AdminWorkflowExecutorProperties
import com.soma.wes.admin.resource.service.AdminWorkflowExecutor
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 켜져 있으면(`app.admin.workflow-executor.enabled`) 주기마다 [AdminWorkflowExecutor.runOnce]를 부른다. 관리자 앱은 1대라 리더 가드가 없다. */
@Component
class AdminWorkflowExecutorScheduler(
    private val executor: AdminWorkflowExecutor,
    private val properties: AdminWorkflowExecutorProperties,
) {

    @Scheduled(fixedDelayString = "\${app.admin.workflow-executor.fixed-delay:10s}")
    fun runOnce() {
        if (!properties.enabled) return
        executor.runOnce()
    }
}
