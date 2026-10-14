package com.soma.wes.admin.resource

import com.soma.wes.admin.dto.request.AdminLoginRequest
import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.admin.service.AdminAuthService
import com.soma.wes.admin.support.AdminSessionCookie
import com.soma.wes.security.filter.AdminMutationHeaderFilter
import com.soma.wes.support.IntegrationTest
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

@IntegrationTest
class AdminNotificationInboxControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val adminAccountFixture: AdminAccountFixture,
    private val adminAuthService: AdminAuthService,
    private val resourceService: AdminResourceService,
    private val jdbcClient: JdbcClient,
) {

    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `수동 알림은 외부 수신자 없이 관리자별 멱등 인박스와 읽음 영수증으로 처리한다`() {
        val actor = adminAccountFixture.관리자("notification-inbox-actor")
        val passwordChangeSession = adminAuthService.login(
            AdminLoginRequest(actor.username, AdminAccountFixture.DEFAULT_PASSWORD),
            "127.0.0.1",
        )
        mockMvc.get("/internal/admin/v1/notifications") {
            cookie(Cookie(AdminSessionCookie.NAME, passwordChangeSession.rawSessionToken))
        }.andExpect { status { isForbidden() } }
        val actorSession = adminAuthService.changePassword(
            actor.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "notification-inbox-actor-password-456",
            ),
            "127.0.0.1",
        )
        val actorCookie = Cookie(AdminSessionCookie.NAME, actorSession.rawSessionToken)
        val target = resourceService.create(
            actor.requiredId,
            AdminResourceType.USER,
            CreateAdminResourceRequest(
                "인박스 대상",
                mapOf(
                    "provider" to "GOOGLE",
                    "providerId" to "notification-inbox-target",
                    "nickname" to "인박스 대상",
                ),
            ),
            "127.0.0.1",
        )
        val workflowBody = """
            {
              "action": "RESEND_NOTIFICATION",
              "reason": "[CUSTOMER_REQUEST] private-inbox@example.com 에게 다시 보내 주세요",
              "expectedVersion": 0,
              "idempotencyKey": "notification-inbox-create-001",
              "confirm": true,
              "fields": { "notificationType": "CUSTOMER_FOLLOW_UP" }
            }
        """.trimIndent()

        mockMvc.get("/internal/admin/v1/notifications")
            .andExpect { status { isUnauthorized() } }

        val created = mockMvc.post("/internal/admin/v1/resources/USER/${target.id}/workflows") {
            cookie(actorCookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = workflowBody
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.details.notificationType") { value("CUSTOMER_FOLLOW_UP") }
            jsonPath("$.details.inboxStatus") { value("OPEN") }
        }.andReturn().response
        val notificationId = objectMapper.readTree(created.contentAsString)
            .path("details").path("notificationId").asLong()

        mockMvc.post("/internal/admin/v1/resources/USER/${target.id}/workflows") {
            cookie(actorCookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = workflowBody
        }.andExpect {
            status { isOk() }
            jsonPath("$.replayed") { value(true) }
            jsonPath("$.details.notificationId") { value(notificationId) }
        }
        assertThat(countInboxRows()).isOne()
        assertThat(countLegacyOutboxRows()).isZero()

        jdbcClient.sql(
            """
            DELETE FROM admin_idempotency_keys
            WHERE action = 'WORKFLOW_RESEND_NOTIFICATION'
              AND idempotency_key = 'notification-inbox-create-001'
            """.trimIndent(),
        ).update()
        mockMvc.post("/internal/admin/v1/resources/USER/${target.id}/workflows") {
            cookie(actorCookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = workflowBody.replace("\"expectedVersion\": 0", "\"expectedVersion\": 1")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ADMIN_409_7") }
        }
        assertThat(countInboxRows()).isOne()

        val inbox = mockMvc.get("/internal/admin/v1/notifications") {
            cookie(actorCookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.unreadCount") { value(1) }
            jsonPath("$.contents[0].id") { value(notificationId) }
            jsonPath("$.contents[0].version") { value(0) }
            jsonPath("$.contents[0].targetType") { value("USER") }
            jsonPath("$.contents[0].targetId") { value(target.id) }
            jsonPath("$.contents[0].workStatus") { value("OPEN") }
            jsonPath("$.contents[0].summary") { value("사용자 #${target.id} · 고객 후속 조치 확인") }
            jsonPath("$.contents[0].read") { value(false) }
        }.andReturn().response.contentAsString
        assertThat(inbox).doesNotContain("private-inbox@example.com", "recipient", "payload")

        mockMvc.post("/internal/admin/v1/notifications/$notificationId/read") {
            cookie(actorCookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":0}"""
        }.andExpect { status { isForbidden() } }

        mockMvc.post("/internal/admin/v1/impersonations") {
            cookie(actorCookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "targetType": "USER",
                  "targetId": ${target.id},
                  "reason": "[TEST_OPERATION] 인박스 읽기 전용 경계 확인"
                }
            """.trimIndent()
        }.andExpect { status { isCreated() } }
        mockMvc.post("/internal/admin/v1/notifications/$notificationId/read") {
            cookie(actorCookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":0}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ADMIN_403_2") }
        }
        mockMvc.delete("/internal/admin/v1/impersonations/current") {
            cookie(actorCookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
        }.andExpect { status { isNoContent() } }

        val firstRead = mockMvc.post("/internal/admin/v1/notifications/$notificationId/read") {
            cookie(actorCookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":0}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.read") { value(true) }
            jsonPath("$.readAt") { isNotEmpty() }
        }.andReturn().response.contentAsString
        val firstReadAt = objectMapper.readTree(firstRead).path("readAt").asText()

        mockMvc.post("/internal/admin/v1/notifications/$notificationId/read") {
            cookie(actorCookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":0}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.readAt") { value(firstReadAt) }
        }
        mockMvc.get("/internal/admin/v1/notifications") {
            cookie(actorCookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.unreadCount") { value(0) }
        }

        mockMvc.post("/internal/admin/v1/resources/USER/${target.id}/workflows") {
            cookie(actorCookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "action": "RESEND_NOTIFICATION",
                  "reason": "[TEST_OPERATION] 금지 필드 확인",
                  "expectedVersion": 1,
                  "idempotencyKey": "notification-inbox-reject-001",
                  "confirm": true,
                  "fields": {
                    "notificationType": "GENERAL_OPERATION_NOTICE",
                    "recipientReference": "forbidden@example.com",
                    "payload": { "message": "원문" }
                  }
                }
            """.trimIndent()
        }.andExpect { status { isBadRequest() } }
        assertThat(countInboxRows()).isOne()

        val permanentEvidence = jdbcClient.sql(
            """
            SELECT COALESCE(reason, '') || COALESCE(changed_fields, '') AS evidence
            FROM admin_audit_logs
            UNION ALL
            SELECT COALESCE(before_snapshot, '') || COALESCE(after_snapshot, '') AS evidence
            FROM admin_entity_revisions
            UNION ALL
            SELECT safe_summary || COALESCE(correlation_id, '') AS evidence
            FROM admin_notification_inbox
            """.trimIndent(),
        ).query { rs, _ -> rs.getString("evidence") }.list().joinToString()
        assertThat(permanentEvidence).doesNotContain(
            "private-inbox@example.com",
            "forbidden@example.com",
            "에게 다시 보내 주세요",
            "원문",
        )
    }

    @Test
    fun `읽음 상태는 최고 관리자별로 분리되고 현재 이벤트 version을 요구한다`() {
        val first = adminAccountFixture.관리자("notification-inbox-first")
        val second = adminAccountFixture.관리자("notification-inbox-second")
        val firstCookie = activeCookie(first.requiredId, "notification-inbox-first-password-456")
        val secondCookie = activeCookie(second.requiredId, "notification-inbox-second-password-456")
        val notificationId = jdbcClient.sql(
            """
            INSERT INTO admin_notification_inbox
                (event_type, target_type, target_id, work_status, safe_summary,
                 idempotency_key_hash, created_by_admin_id, version, created_at, updated_at)
            VALUES
                ('PROCESSING_REVIEW', 'PHOTO', 42, 'OPEN', '사진 #42 · 처리 작업 상태 확인',
                 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', :adminId, 0,
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("adminId", first.requiredId).query { rs, _ -> rs.getLong(1) }.single()

        markRead(firstCookie, notificationId, 0).andExpect { status { isOk() } }
        mockMvc.get("/internal/admin/v1/notifications") { cookie(secondCookie) }.andExpect {
            status { isOk() }
            jsonPath("$.unreadCount") { value(1) }
            jsonPath("$.contents[0].read") { value(false) }
        }

        jdbcClient.sql(
            "UPDATE admin_notification_inbox SET version=version+1, updated_at=CURRENT_TIMESTAMP WHERE id=:id",
        ).param("id", notificationId).update()
        markRead(secondCookie, notificationId, 0).andExpect { status { isConflict() } }
        markRead(secondCookie, notificationId, 1).andExpect {
            status { isOk() }
            jsonPath("$.read") { value(true) }
            jsonPath("$.version") { value(1) }
        }
    }

    private fun activeCookie(adminId: Long, password: String): Cookie {
        val session = adminAuthService.changePassword(
            adminId,
            ChangeAdminPasswordRequest(AdminAccountFixture.DEFAULT_PASSWORD, password),
            "127.0.0.1",
        )
        return Cookie(AdminSessionCookie.NAME, session.rawSessionToken)
    }

    private fun markRead(cookie: Cookie, notificationId: Long, expectedVersion: Long) =
        mockMvc.post("/internal/admin/v1/notifications/$notificationId/read") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":$expectedVersion}"""
        }

    private fun countInboxRows(): Long = jdbcClient.sql("SELECT COUNT(*) FROM admin_notification_inbox")
        .query { rs, _ -> rs.getLong(1) }.single()

    private fun countLegacyOutboxRows(): Long = jdbcClient.sql("SELECT COUNT(*) FROM admin_notification_outbox")
        .query { rs, _ -> rs.getLong(1) }.single()
}
