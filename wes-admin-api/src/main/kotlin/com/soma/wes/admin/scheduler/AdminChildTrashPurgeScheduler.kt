package com.soma.wes.admin.scheduler

import com.soma.wes.admin.resource.service.AdminChildTrashPurgeService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 매시 45분에 [AdminChildTrashPurgeService.purgeExpired]를 부른다. 관리자 앱은 1대라 리더 가드가 없다(`entrypoint.md`). */
@Component
class AdminChildTrashPurgeScheduler(
    private val purgeService: AdminChildTrashPurgeService,
) {

    @Scheduled(cron = "0 45 * * * *")
    fun purgeExpired() {
        purgeService.purgeExpired()
    }
}
