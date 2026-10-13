package com.soma.wes.admin.service

import com.soma.wes.admin.domain.AdminAccountStatus
import com.soma.wes.admin.domain.AdminEventType
import com.soma.wes.admin.dto.request.AdminReasonRequest
import com.soma.wes.admin.dto.request.ChangeAdminStatusRequest
import com.soma.wes.admin.dto.request.CreateAdminAccountRequest
import com.soma.wes.admin.exception.AdminAuthenticationException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.repository.AdminAuthEventRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class AdminAccountServiceTest @Autowired constructor(
    private val adminAccountService: AdminAccountService,
    private val adminAuthService: AdminAuthService,
    private val adminSessionService: AdminSessionService,
    private val adminAccountFixture: AdminAccountFixture,
    private val adminAccountRepository: AdminAccountRepository,
    private val adminAuthEventRepository: AdminAuthEventRepository,
) {

    @Test
    fun `새 관리자 계정은 한 번만 표시하는 임시 비밀번호와 변경 강제 상태로 만든다`() {
        // given
        val actor = adminAccountFixture.관리자("actor")

        // when
        val result = adminAccountService.create(
            actorAdminId = actor.requiredId,
            request = CreateAdminAccountRequest(
                username = "new-owner",
                displayName = "신규 관리자",
                reason = "운영 담당자 추가",
            ),
            sourceAddress = "127.0.0.1",
        )

        // then
        assertThat(result.temporaryPassword).hasSizeGreaterThanOrEqualTo(12)
        assertThat(result.account.mustChangePassword).isTrue()
        assertThat(adminAuthService.login(
            com.soma.wes.admin.dto.request.AdminLoginRequest("new-owner", result.temporaryPassword),
            "127.0.0.1",
        ).response.admin.id).isEqualTo(result.account.id)
        assertThat(adminAuthEventRepository.findAll().map { it.eventType })
            .contains(AdminEventType.ACCOUNT_CREATED)
    }

    @Test
    fun `계정을 정지하면 기존 세션을 즉시 폐기한다`() {
        // given
        val actor = adminAccountFixture.관리자("status-actor")
        val target = adminAccountFixture.관리자("status-target")
        val session = adminAuthService.login(
            com.soma.wes.admin.dto.request.AdminLoginRequest(
                target.username,
                AdminAccountFixture.DEFAULT_PASSWORD,
            ),
            "127.0.0.1",
        )

        // when
        val response = adminAccountService.changeStatus(
            actorAdminId = actor.requiredId,
            targetAdminId = target.requiredId,
            request = ChangeAdminStatusRequest(
                status = AdminAccountStatus.SUSPENDED,
                reason = "담당 업무 종료",
            ),
            sourceAddress = "127.0.0.1",
        )

        // then
        assertThat(response.status).isEqualTo(AdminAccountStatus.SUSPENDED)
        assertThatThrownBy { adminSessionService.authenticate(session.rawSessionToken) }
            .isInstanceOf(AdminAuthenticationException::class.java)
    }

    @Test
    fun `다른 관리자의 임시 비밀번호를 발급하면 기존 세션을 폐기한다`() {
        // given
        val actor = adminAccountFixture.관리자("reset-actor")
        val target = adminAccountFixture.관리자("reset-target")
        val session = adminAuthService.login(
            com.soma.wes.admin.dto.request.AdminLoginRequest(
                target.username,
                AdminAccountFixture.DEFAULT_PASSWORD,
            ),
            "127.0.0.1",
        )

        // when
        val response = adminAccountService.issueTemporaryPassword(
            actorAdminId = actor.requiredId,
            targetAdminId = target.requiredId,
            request = AdminReasonRequest("분실 신고"),
            sourceAddress = "127.0.0.1",
        )

        // then
        assertThat(response.temporaryPassword).isNotBlank()
        assertThat(response.account.mustChangePassword).isTrue()
        assertThatThrownBy { adminSessionService.authenticate(session.rawSessionToken) }
            .isInstanceOf(AdminAuthenticationException::class.java)
    }

    @Test
    fun `모든 관리자가 잠겨도 서버 CLI 복구로 계정을 활성화하고 임시 비밀번호를 설정한다`() {
        // given
        val account = adminAccountFixture.관리자("recovery-owner")
        val previousSession = adminAuthService.login(
            com.soma.wes.admin.dto.request.AdminLoginRequest(
                account.username,
                AdminAccountFixture.DEFAULT_PASSWORD,
            ),
            "127.0.0.1",
        )
        // 로그인 성공이 계정의 lastLoginAt과 version을 올렸으므로 최신 행을 다시 읽는다.
        val latestAccount = adminAccountRepository.findById(account.requiredId).orElseThrow()
        latestAccount.suspend()
        adminAccountRepository.save(latestAccount)

        // when
        val recovered = adminAccountService.recoverFromCli(
            username = account.username,
            displayName = account.displayName,
            temporaryPassword = "recovered-admin-password-789",
        )

        // then
        assertThat(recovered.status).isEqualTo(AdminAccountStatus.ACTIVE)
        assertThat(recovered.mustChangePassword).isTrue()
        assertThatThrownBy { adminSessionService.authenticate(previousSession.rawSessionToken) }
            .isInstanceOf(AdminAuthenticationException::class.java)
        assertThat(adminAuthService.login(
            com.soma.wes.admin.dto.request.AdminLoginRequest(
                account.username,
                "recovered-admin-password-789",
            ),
            "127.0.0.1",
        ).response.admin.id).isEqualTo(account.requiredId)
        assertThat(adminAuthEventRepository.findAll().map { it.eventType })
            .contains(AdminEventType.CLI_RECOVERY)
    }
}
