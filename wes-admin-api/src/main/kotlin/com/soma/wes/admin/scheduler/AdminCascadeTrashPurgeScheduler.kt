package com.soma.wes.admin.scheduler

import com.soma.wes.admin.resource.service.AdminCascadeTrashPurgeService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 매시 40분에 [AdminCascadeTrashPurgeService.purgeExpired]를 부른다. 관리자 앱은 1대라 리더 가드가 없다(`entrypoint.md`). */
@Component
class AdminCascadeTrashPurgeScheduler(
    private val purgeService: AdminCascadeTrashPurgeService,
) {

    @Scheduled(cron = "0 40 * * * *")
    fun purgeExpired() {
        purgeService.purgeExpired()
    }
}
