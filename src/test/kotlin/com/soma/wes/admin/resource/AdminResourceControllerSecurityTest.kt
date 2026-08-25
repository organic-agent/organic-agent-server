package com.soma.wes.admin.resource

import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.service.AdminAuthService
import com.soma.wes.admin.support.AdminSessionCookie
import com.soma.wes.security.filter.AdminMutationHeaderFilter
import com.soma.wes.support.IntegrationTest
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@IntegrationTest
class AdminResourceControllerSecurityTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val adminAccountFixture: AdminAccountFixture,
    private val adminAuthService: AdminAuthService,
) {

    @Test
    fun `관리자 세션 없는 요청은 리소스와 설정 API에 접근할 수 없다`() {
        mockMvc.get("/internal/admin/v1/resources").andExpect { status { isUnauthorized() } }
        mockMvc.get("/internal/admin/v1/system-settings").andExpect { status { isUnauthorized() } }
        mockMvc.get("/internal/admin/v1/operations/overview").andExpect { status { isUnauthorized() } }
        mockMvc.get("/internal/admin/v1/resources/USER/1/context").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `최고 관리자 세션은 생성 검색 상세 설정 조회를 사용하고 비밀값은 받지 않는다`() {
        val account = adminAccountFixture.관리자("resource-controller")
        val changed = adminAuthService.changePassword(
            account.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "resource-controller-password-456",
            ),
            "127.0.0.1",
        )
        val cookie = Cookie(AdminSessionCookie.NAME, changed.rawSessionToken)

        val created = mockMvc.post("/internal/admin/v1/resources/USER") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "reason": "고객 지원으로 계정 생성",
                  "fields": {
                    "provider": "GOOGLE",
                    "providerId": "controller-provider-secret",
                    "nickname": "컨트롤러 사용자",
                    "email": "controller-private@example.com"
                  }
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.fields.providerId") { value("[MASKED]") }
            jsonPath("$.version") { value(0) }
        }.andReturn().response
        assertThat(created.contentAsString).doesNotContain("controller-provider-secret")
        val createdId = tools.jackson.databind.json.JsonMapper.builder().build()
            .readTree(created.contentAsString).path("id").asLong()

        mockMvc.get("/internal/admin/v1/resources") {
            cookie(cookie)
            param("query", "컨트롤러")
            param("types", "USER")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.contents[0].type") { value("USER") }
            jsonPath("$.contents[0].label") { value("컨트롤러 사용자") }
        }

        mockMvc.get("/internal/admin/v1/system-settings") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.secretsMasked") { value(true) }
            jsonPath("$.mutable") { value(false) }
        }

        mockMvc.get("/internal/admin/v1/resources/USER/$createdId/context") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resource.id") { value(createdId) }
            jsonPath("$.facts.activeSessions") { value(0) }
            jsonPath("$.relations") { isArray() }
        }

        mockMvc.get("/internal/admin/v1/operations/overview") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceCounts.USER.total") { value(1) }
            jsonPath("$.operationalIssues") { isArray() }
            jsonPath("$.trashPendingCount") { isNumber() }
            jsonPath("$.recentAudits") { isArray() }
        }
    }
}
