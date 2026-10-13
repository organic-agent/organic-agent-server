package com.soma.wes.admin.fixture

import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.support.AdminPasswordHasher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZonedDateTime

@Component
class AdminAccountFixture(
    private val adminAccountRepository: AdminAccountRepository,
    private val passwordHasher: AdminPasswordHasher,
    private val clock: Clock,
) {

    fun 관리자(
        username: String,
        password: String = DEFAULT_PASSWORD,
        displayName: String = "최고 관리자",
    ): AdminAccount =
        adminAccountRepository.save(
            AdminAccount.of(
                username = username,
                displayName = displayName,
                passwordHash = passwordHasher.hash(password),
                now = ZonedDateTime.now(clock),
                createdByAdminId = null,
            ),
        )

    companion object {
        const val DEFAULT_PASSWORD = "initial-admin-password-123"
    }
}
