package com.soma.wes.admin.resource

import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.admin.service.AdminAuthService
import com.soma.wes.admin.support.AdminSessionCookie
import com.soma.wes.analysis.service.port.AiTaskSender
import com.soma.wes.photo.service.port.PhotoStorage
import com.soma.wes.security.filter.AdminMutationHeaderFilter
import com.soma.wes.support.IntegrationTest
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@IntegrationTest
class AdminWorkflowControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val adminAccountFixture: AdminAccountFixture,
    private val adminAuthService: AdminAuthService,
    private val resourceService: AdminResourceService,
) {

    @MockitoBean
    private lateinit var photoStorage: PhotoStorage

    @MockitoBean
    private lateinit var aiTaskSender: AiTaskSender

    @Test
    fun `워크플로 mutation은 관리자 세션과 mutation header 및 명시적 확인을 모두 요구한다`() {
        mockMvc.post("/internal/admin/v1/resources/STUDIO/1/workflows") {
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = workflowBody(confirm = true)
        }.andExpect { status { isUnauthorized() } }

        val account = adminAccountFixture.관리자("workflow-controller-owner")
        val session = adminAuthService.changePassword(
            account.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "workflow-controller-password-456",
            ),
            "127.0.0.1",
        )
        val cookie = Cookie(AdminSessionCookie.NAME, session.rawSessionToken)
        val user = resourceService.create(
            account.requiredId,
            AdminResourceType.USER,
            CreateAdminResourceRequest(
                "컨트롤러 사용자",
                mapOf("provider" to "KAKAO", "providerId" to "workflow-controller", "nickname" to "컨트롤러"),
            ),
            "127.0.0.1",
        )
        val studio = resourceService.create(
            account.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "컨트롤러 스튜디오",
                mapOf("ownerUserId" to user.id, "name" to "컨트롤러 스튜디오", "galleryUrl" to "workflow-controller"),
            ),
            "127.0.0.1",
        )

        mockMvc.post("/internal/admin/v1/resources/STUDIO/${studio.id}/workflows") {
            cookie(cookie)
            contentType = MediaType.APPLICATION_JSON
            content = workflowBody(confirm = true)
        }.andExpect { status { isForbidden() } }

        mockMvc.post("/internal/admin/v1/resources/STUDIO/${studio.id}/workflows") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = workflowBody(confirm = false)
        }.andExpect { status { isBadRequest() } }

        mockMvc.post("/internal/admin/v1/resources/STUDIO/${studio.id}/workflows") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = workflowBody(confirm = true)
        }.andExpect {
            status { isOk() }
            jsonPath("$.action") { value("SET_STUDIO_RETOUCH_CAPABILITY") }
            jsonPath("$.targetType") { value("STUDIO") }
            jsonPath("$.targetId") { value(studio.id) }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.replayed") { value(false) }
        }
    }

    private fun workflowBody(confirm: Boolean): String =
        """
        {
          "action": "SET_STUDIO_RETOUCH_CAPABILITY",
          "reason": "고객 요청에 따른 보정 기능 활성화",
          "expectedVersion": 0,
          "idempotencyKey": "workflow-controller-001",
          "confirm": $confirm,
          "fields": {
            "capability": "AI_RETOUCH",
            "enabled": true
          }
        }
        """.trimIndent()
}
