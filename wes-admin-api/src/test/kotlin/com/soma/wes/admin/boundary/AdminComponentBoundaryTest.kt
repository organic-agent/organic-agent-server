package com.soma.wes.admin.boundary

import com.soma.wes.admin.audit.support.AdminRevisionPurgeScheduler
import com.soma.wes.admin.resource.service.AdminCascadeTrashPurgeService
import com.soma.wes.auth.support.OAuthStateCleaner
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.support.TrashPurgeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext

@IntegrationTest
class AdminComponentBoundaryTest(
    private val applicationContext: ApplicationContext,
) {

    @Test
    fun `admin runtime schedules only admin maintenance jobs`() {
        assertThat(applicationContext.getBeansOfType(OAuthStateCleaner::class.java)).isEmpty()
        assertThat(applicationContext.getBeansOfType(TrashPurgeScheduler::class.java)).isEmpty()
        assertThat(applicationContext.getBeansOfType(AdminRevisionPurgeScheduler::class.java)).hasSize(1)
        assertThat(applicationContext.getBeansOfType(AdminCascadeTrashPurgeService::class.java)).hasSize(1)
    }
}
