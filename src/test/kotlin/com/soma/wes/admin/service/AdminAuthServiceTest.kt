package com.soma.wes.admin.service

import com.soma.wes.admin.domain.AdminAccountStatus
import com.soma.wes.admin.domain.AdminEventType
import com.soma.wes.admin.dto.request.AdminLoginRequest
import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.exception.AdminAuthenticationException
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.repository.AdminAuthEventRepository
import com.soma.wes.admin.repository.AdminSessionRepository
import com.soma.wes.admin.support.AdminSessionTokenHasher
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZonedDateTime

@IntegrationTest
class AdminAuthServiceTest @Autowired constructor(
    private val adminAuthService: AdminAuthService,
    private val adminSessionService: AdminSessionService,
    private val adminAccountFixture: AdminAccountFixture,
    private val adminAccountRepository: AdminAccountRepository,
    private val adminSessionRepository: AdminSessionRepository,
    private val adminAuthEventRepository: AdminAuthEventRepository,
    private val tokenHasher: AdminSessionTokenHasher,
) {

    @Nested
    @DisplayName("로그인할 때")
    inner class Login {

        @Test
        fun `Argon2id 비밀번호가 맞으면 원문을 저장하지 않는 8시간 세션을 발급한다`() {
            // given
            adminAccountFixture.관리자("owner")
            val before = ZonedDateTime.now()

            // when
            val result = login("owner", AdminAccountFixture.DEFAULT_PASSWORD)

            // then
            val stored = adminSessionRepository.findById(tokenHasher.hash(result.rawSessionToken)).orElseThrow()
            assertThat(adminSessionRepository.findById(result.rawSessionToken)).isEmpty
            assertThat(stored.tokenHash).isNotEqualTo(result.rawSessionToken)
            assertThat(result.response.absoluteExpiresAt)
                .isBetween(before.plusHours(7).plusMinutes(59), before.plusHours(8).plusMinutes(1))
            assertThat(result.response.admin.mustChangePassword).isTrue()
            assertThat(adminAuthEventRepository.findAll().single().eventType)
                .isEqualTo(AdminEventType.LOGIN_SUCCEEDED)
        }

        @Test
        fun `비밀번호를 5회 틀리면 30분 동안 잠그고 기존 세션을 폐기한다`() {
            // given
            val account = adminAccountFixture.관리자("locked-owner")
            val validSession = login("locked-owner", AdminAccountFixture.DEFAULT_PASSWORD)

            // when
            repeat(4) {
                assertInvalidCredentials("locked-owner")
            }
            assertThatThrownBy { login("locked-owner", "wrong-password-value") }
                .isInstanceOf(AdminAuthenticationException::class.java)
                .extracting("errorCode")
                .isEqualTo(AdminErrorCode.ACCOUNT_LOCKED)

            // then
            val locked = adminAccountRepository.findById(account.requiredId).orElseThrow()
            assertThat(locked.failedLoginAttempts).isEqualTo(5)
            assertThat(locked.lockedUntil).isAfter(ZonedDateTime.now().plusMinutes(29))
            assertThatThrownBy { adminSessionService.authenticate(validSession.rawSessionToken) }
                .isInstanceOf(AdminAuthenticationException::class.java)
                .extracting("errorCode")
                .isEqualTo(AdminErrorCode.SESSION_INVALID)
            assertThat(adminAuthEventRepository.findAll().map { it.eventType })
                .contains(AdminEventType.ACCOUNT_LOCKED)
        }

        @Test
        fun `정지된 계정은 올바른 비밀번호로도 로그인할 수 없다`() {
            // given
            val account = adminAccountFixture.관리자("suspended-owner")
            account.suspend()
            adminAccountRepository.save(account)

            // when & then
            assertThatThrownBy { login("suspended-owner", AdminAccountFixture.DEFAULT_PASSWORD) }
                .isInstanceOf(AdminAuthenticationException::class.java)
                .extracting("errorCode")
                .isEqualTo(AdminErrorCode.ACCOUNT_SUSPENDED)
            assertThat(adminAccountRepository.findById(account.requiredId).orElseThrow().status)
                .isEqualTo(AdminAccountStatus.SUSPENDED)
        }
    }

    @Test
    fun `비밀번호 변경은 이전 세션을 모두 폐기하고 새 세션을 발급한다`() {
        // given
        val account = adminAccountFixture.관리자("password-owner")
        val oldSession = login("password-owner", AdminAccountFixture.DEFAULT_PASSWORD)

        // when
        val changed = adminAuthService.changePassword(
            adminId = account.requiredId,
            request = ChangeAdminPasswordRequest(
                currentPassword = AdminAccountFixture.DEFAULT_PASSWORD,
                newPassword = "changed-admin-password-456",
            ),
            sourceAddress = "127.0.0.1",
        )

        // then
        assertThatThrownBy { adminSessionService.authenticate(oldSession.rawSessionToken) }
            .isInstanceOf(AdminAuthenticationException::class.java)
            .extracting("errorCode")
            .isEqualTo(AdminErrorCode.SESSION_INVALID)
        assertThat(adminSessionService.authenticate(changed.rawSessionToken).isAuthenticated).isTrue()
        assertThat(changed.response.admin.mustChangePassword).isFalse()
        assertThat(adminAuthEventRepository.findAll().map { it.eventType })
            .contains(AdminEventType.PASSWORD_CHANGED)
    }

    private fun login(
        username: String,
        password: String,
    ) = adminAuthService.login(
        AdminLoginRequest(username = username, password = password),
        sourceAddress = "127.0.0.1",
    )

    private fun assertInvalidCredentials(username: String) {
        assertThatThrownBy { login(username, "wrong-password-value") }
            .isInstanceOf(AdminAuthenticationException::class.java)
            .extracting("errorCode")
            .isEqualTo(AdminErrorCode.INVALID_CREDENTIALS)
    }
}
