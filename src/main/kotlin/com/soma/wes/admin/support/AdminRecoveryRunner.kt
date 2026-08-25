package com.soma.wes.admin.support

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.service.AdminAccountService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component

@Component
class AdminRecoveryRunner(
    private val adminAccountService: AdminAccountService,
    private val applicationContext: ConfigurableApplicationContext,
    @Value("\${wes.admin.recovery.enabled:false}")
    private val enabled: Boolean,
    @Value("\${wes.admin.recovery.username:}")
    private val username: String,
    @Value("\${wes.admin.recovery.display-name:최고 관리자}")
    private val displayName: String,
    @Value("\${wes.admin.recovery.temporary-password:}")
    private val temporaryPassword: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!enabled) {
            return
        }
        if (username.isBlank() || temporaryPassword.isBlank()) {
            throw AdminException(AdminErrorCode.PASSWORD_POLICY_VIOLATION)
        }

        val account = adminAccountService.recoverFromCli(username, displayName, temporaryPassword)
        log.info("최고 관리자 CLI 복구 완료: adminId={}, username={}", account.id, account.username)
        applicationContext.close()
    }
}
