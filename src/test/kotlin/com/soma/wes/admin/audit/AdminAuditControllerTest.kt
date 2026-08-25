package com.soma.wes.admin.audit

import com.soma.wes.admin.domain.AdminAccountStatus
import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.dto.request.ChangeAdminStatusRequest
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.service.AdminAccountService
import com.soma.wes.admin.service.AdminAuthService
import com.soma.wes.admin.support.AdminSessionCookie
import com.soma.wes.support.IntegrationTest
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@IntegrationTest
class AdminAuditControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val adminAccountFixture: AdminAccountFixture,
    private val adminAccountService: AdminAccountService,
    private val adminAuthService: AdminAuthService,
) {

    @Test
    fun `관리자와 작업 유형 및 대상별로 감사 로그를 검색하고 리비전을 조회한다`() {
        val actor = adminAccountFixture.관리자("query-actor")
        val target = adminAccountFixture.관리자("query-target")
        adminAccountService.changeStatus(
            actorAdminId = actor.requiredId,
            targetAdminId = target.requiredId,
            request = ChangeAdminStatusRequest(AdminAccountStatus.SUSPENDED, "감사 조회 검증"),
            sourceAddress = "127.0.0.1",
        )
        adminAuthService.login(
            com.soma.wes.admin.dto.request.AdminLoginRequest(actor.username, AdminAccountFixture.DEFAULT_PASSWORD),
            "127.0.0.1",
        )
        val changed = adminAuthService.changePassword(
            actor.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "audit-controller-password-456",
            ),
            "127.0.0.1",
        )
        val cookie = Cookie(AdminSessionCookie.NAME, changed.rawSessionToken)

        mockMvc.get("/internal/admin/v1/audit-logs") {
            cookie(cookie)
            param("actorAdminId", actor.requiredId.toString())
            param("targetType", "ADMIN_ACCOUNT")
            param("targetId", target.requiredId.toString())
            param("action", "ACCOUNT_SUSPENDED")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.contents[0].reason") { value("감사 조회 검증") }
            jsonPath("$.contents[0].changedFields[0]") { value("status") }
        }

        mockMvc.get("/internal/admin/v1/audit-logs/targets/ADMIN_ACCOUNT/${target.requiredId}/revisions") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].before.status") { value("ACTIVE") }
            jsonPath("$[0].after.status") { value("SUSPENDED") }
        }
    }
}
