package com.soma.wes.admin.resource

import com.soma.wes.admin.dto.request.AdminLoginRequest
import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.admin.service.AdminAuthService
import com.soma.wes.admin.support.AdminSessionCookie
import com.soma.wes.global.filter.HttpLoggingFilter
import com.soma.wes.security.filter.AdminMutationHeaderFilter
import com.soma.wes.support.IntegrationTest
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@IntegrationTest
class AdminResourceControllerSecurityTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val adminAccountFixture: AdminAccountFixture,
    private val adminAuthService: AdminAuthService,
    private val adminResourceService: AdminResourceService,
    private val jdbcClient: JdbcClient,
) {

    @Test
    fun `관리자 세션 없는 요청은 리소스와 설정 API에 접근할 수 없다`() {
        mockMvc.get("/internal/admin/v1/resources").andExpect { status { isUnauthorized() } }
        mockMvc.get("/internal/admin/v1/resources/USER/1").andExpect { status { isUnauthorized() } }
        mockMvc.get("/internal/admin/v1/system-settings").andExpect { status { isUnauthorized() } }
        mockMvc.get("/internal/admin/v1/operations/overview").andExpect { status { isUnauthorized() } }
        mockMvc.get("/internal/admin/v1/operations/trash").andExpect { status { isUnauthorized() } }
        mockMvc.get("/internal/admin/v1/operations/trash/children").andExpect { status { isUnauthorized() } }
        mockMvc.post("/internal/admin/v1/operations/trash/1/restore") {
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
        }.andExpect { status { isUnauthorized() } }
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
            jsonPath("$.sections.userNotifications.length()") { value(0) }
            jsonPath("$.sections.userNotificationSettings[0].userId") { value(createdId) }
            jsonPath("$.sections.userNotificationSettings[0].emailEnabled") { value(true) }
            jsonPath("$.sections.userNotificationSettings[0].browserEnabled") { value(true) }
            jsonPath("$.sections.userNotificationSettings[0].settingsPersisted") { value(false) }
            jsonPath("$.relations") { isArray() }
        }

        mockMvc.get("/internal/admin/v1/operations/overview") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceCounts.USER.total") { value(1) }
            jsonPath("$.operationalIssues") { isArray() }
            jsonPath("$.recentFailedOperations") { isArray() }
            jsonPath("$.trashPendingCount") { isNumber() }
            jsonPath("$.recentAudits") { isArray() }
        }

        mockMvc.get("/internal/admin/v1/operations/trash/children") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$") { isArray() }
        }
    }

    @Test
    fun `리소스 목록 상세 컨텍스트 조회는 응답 값 없이 메타데이터 감사만 남긴다`() {
        val account = adminAccountFixture.관리자("resource-read-audit")
        val changed = adminAuthService.changePassword(
            account.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "resource-read-audit-password-456",
            ),
            "127.0.0.1",
        )
        val cookie = Cookie(AdminSessionCookie.NAME, changed.rawSessionToken)
        val adminSessionId = jdbcClient.sql(
            "SELECT session_id::TEXT FROM admin_sessions WHERE admin_id = :adminId AND revoked_at IS NULL",
        )
            .param("adminId", account.requiredId)
            .query { rs, _ -> rs.getString(1) }
            .single()
        val privateQuery = "resource-read-private@example.com"
        val privateProviderId = "resource-read-provider-secret"
        val privateNickname = "조회 감사 민감 이름"
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()

        val createdResponse = mockMvc.post("/internal/admin/v1/resources/USER") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "reason": "조회 감사 대상 준비",
                  "fields": {
                    "provider": "GOOGLE",
                    "providerId": "$privateProviderId",
                    "nickname": "$privateNickname",
                    "email": "$privateQuery"
                  }
                }
            """.trimIndent()
        }.andExpect { status { isCreated() } }.andReturn().response
        val userId = mapper.readTree(createdResponse.contentAsString).path("id").asLong()
        val revisionCountBeforeReads = countEntityRevisions()

        val listResponse = mockMvc.get("/internal/admin/v1/resources") {
            cookie(cookie)
            param("query", privateQuery)
            param("types", "USER")
            param("page", "0")
            param("size", "10")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.contents[0].id") { value(userId) }
        }.andReturn().response
        val detailResponse = mockMvc.get("/internal/admin/v1/resources/USER/$userId") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(userId) }
        }.andReturn().response
        val contextResponse = mockMvc.get("/internal/admin/v1/resources/USER/$userId/context") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resource.id") { value(userId) }
        }.andReturn().response

        val audits = jdbcClient.sql(
            """
            SELECT actor_admin_id, actor_username_snapshot, target_type, target_id, target_label,
                   source_address, reason, changed_fields, revision_number, correlation_id
            FROM admin_audit_logs
            WHERE action = 'RESOURCE_VIEWED'
            ORDER BY id
            """.trimIndent(),
        ).query { rs, _ ->
            ResourceReadAuditRow(
                actorAdminId = rs.getLong("actor_admin_id"),
                actorUsername = rs.getString("actor_username_snapshot"),
                targetType = rs.getString("target_type"),
                targetId = rs.getString("target_id"),
                targetLabel = rs.getString("target_label"),
                sourceAddress = rs.getString("source_address"),
                reason = rs.getString("reason"),
                changedFields = rs.getString("changed_fields"),
                hasRevision = rs.getObject("revision_number") != null,
                correlationId = rs.getString("correlation_id"),
            )
        }.list()

        assertThat(audits).hasSize(3)
        assertThat(audits.map { it.targetType }).containsExactly("ADMIN_OPERATION", "USER", "USER")
        assertThat(audits.map { it.targetId }).containsExactly("RESOURCE_LIST", userId.toString(), userId.toString())
        assertThat(audits.map { it.targetLabel }).containsExactly(
            "ADMIN_OPERATION #RESOURCE_LIST",
            "USER #$userId",
            "USER #$userId",
        )
        assertThat(audits[0].reason).contains(
            "route=RESOURCE_LIST",
            "page=0",
            "size=10",
            "queryFilterPresent=true",
            "typeFilterPresent=true",
            "typeFilterCount=1",
            "returnedCount=1",
            "totalCount=1",
        )
        assertThat(audits[1].reason).contains("route=RESOURCE_DETAIL")
        assertThat(audits[2].reason).contains("route=RESOURCE_CONTEXT")
        assertThat(audits).allSatisfy { audit ->
            assertThat(audit.actorAdminId).isEqualTo(account.requiredId)
            assertThat(audit.actorUsername).isEqualTo("ADMIN #${account.requiredId}")
            assertThat(audit.sourceAddress).isNull()
            assertThat(audit.reason).contains("adminSessionId=$adminSessionId")
            assertThat(audit.changedFields).isNull()
            assertThat(audit.hasRevision).isFalse()
            assertThat(audit.correlationId).matches(REQUEST_TRACE_REGEX)
        }
        assertThat(audits.map { it.correlationId }).containsExactly(
            listResponse.getHeader(HttpLoggingFilter.CORRELATION_ID_HEADER),
            detailResponse.getHeader(HttpLoggingFilter.CORRELATION_ID_HEADER),
            contextResponse.getHeader(HttpLoggingFilter.CORRELATION_ID_HEADER),
        )
        assertThat(audits.joinToString("|") { "${it.targetLabel} ${it.reason}" })
            .doesNotContain(privateQuery, privateProviderId, privateNickname)
        assertThat(countEntityRevisions()).isEqualTo(revisionCountBeforeReads)
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM admin_entity_revisions WHERE operation = 'RESOURCE_VIEWED'")
                .query { rs, _ -> rs.getLong(1) }
                .single(),
        ).isZero()
    }

    @Test
    fun `사진 컨텍스트 알림은 수신자와 payload 원문 없이 안전한 요약만 반환한다`() {
        val account = adminAccountFixture.관리자("photo-context-mask")
        val changed = adminAuthService.changePassword(
            account.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "photo-context-mask-password-456",
            ),
            "127.0.0.1",
        )
        val cookie = Cookie(AdminSessionCookie.NAME, changed.rawSessionToken)
        fun create(type: AdminResourceType, fields: Map<String, Any?>) = adminResourceService.create(
            account.requiredId,
            type,
            CreateAdminResourceRequest("[TEST_OPERATION] 컨텍스트 마스킹 준비", fields),
            "127.0.0.1",
        )
        val user = create(
            AdminResourceType.USER,
            mapOf("provider" to "GOOGLE", "providerId" to "context-mask-owner", "nickname" to "소유자"),
        )
        val studio = create(
            AdminResourceType.STUDIO,
            mapOf("ownerUserId" to user.id, "name" to "마스킹 스튜디오", "galleryUrl" to "context-mask"),
        )
        val gallery = create(
            AdminResourceType.GALLERY,
            mapOf("workspaceId" to studio.id, "title" to "마스킹 갤러리"),
        )
        val photo = create(
            AdminResourceType.PHOTO,
            mapOf(
                "galleryId" to gallery.id,
                "storageKey" to "galleries/${gallery.id}/context.jpg",
                "originalFileName" to "context.jpg",
                "contentType" to "image/jpeg",
            ),
        )
        jdbcClient.sql(
            """
            INSERT INTO admin_notification_outbox
                (notification_type, recipient_reference, source_type, source_id, payload,
                 status, attempt_count, actor_admin_id, reason, created_at, updated_at)
            VALUES
                ('PHOTO_READY', 'private-recipient@example.com', 'PHOTO', :photoId,
                 '{"customerName":"김민수","token":"must-not-leak","delivery":"서울시"}'::JSONB,
                 'PENDING', 0, :actorId, 'reasonCategory=TEST_OPERATION operatorReasonProvided=true',
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("photoId", photo.id).param("actorId", account.requiredId).update()
        jdbcClient.sql(
            """
            INSERT INTO admin_notification_inbox
                (event_type, target_type, target_id, work_status, safe_summary, idempotency_key_hash,
                 created_by_admin_id, version, created_at, updated_at)
            VALUES
                ('PROCESSING_REVIEW', 'PHOTO', :photoId, 'OPEN', '사진 처리 작업 상태 확인',
                 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                 :actorId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("photoId", photo.id).param("actorId", account.requiredId).update()

        val response = mockMvc.get("/internal/admin/v1/resources/PHOTO/${photo.id}/context") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.sections.notifications.length()") { value(1) }
            jsonPath("$.sections.notifications[0].notificationType") { value("PROCESSING_REVIEW") }
            jsonPath("$.sections.notifications[0].status") { value("OPEN") }
            jsonPath("$.sections.notifications[0].summary") { value("사진 처리 작업 상태 확인") }
        }.andReturn().response.contentAsString
        assertThat(response).doesNotContain(
            "private-recipient@example.com",
            "김민수",
            "must-not-leak",
            "서울시",
        )
    }

    @Test
    fun `관리자 계정 감사 휴지통 운영 설정 조회와 조회 실패는 메타데이터 감사만 남긴다`() {
        val account = adminAccountFixture.관리자("admin-read-boundary")
        val changed = adminAuthService.changePassword(
            account.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "admin-read-boundary-password-456",
            ),
            "127.0.0.1",
        )
        val cookie = Cookie(AdminSessionCookie.NAME, changed.rawSessionToken)
        val revisionCountBeforeReads = countEntityRevisions()

        listOf(
            "/internal/admin/v1/admins",
            "/internal/admin/v1/audit-logs",
            "/internal/admin/v1/operations/trash",
            "/internal/admin/v1/operations/trash/children",
            "/internal/admin/v1/operations/overview",
            "/internal/admin/v1/system-settings",
            "/internal/admin/v1/operations/observability-links?correlationId=0123456789abcdef",
        ).forEach { uri ->
            mockMvc.get(uri) { cookie(cookie) }.andExpect { status { isOk() } }
        }
        mockMvc.get("/internal/admin/v1/resources/USER/9223372036854775807") {
            cookie(cookie)
        }.andExpect { status { isNotFound() } }
        mockMvc.get("/internal/admin/v1/audit-logs") {
            cookie(cookie)
            param("from", "2026-08-27T12:00:00+09:00")
            param("to", "2026-08-26T12:00:00+09:00")
        }.andExpect { status { isBadRequest() } }

        val audits = jdbcClient.sql(
            """
            SELECT actor_admin_id, outcome, target_type, target_id, reason, revision_number
            FROM admin_audit_logs
            WHERE action = 'RESOURCE_VIEWED'
            ORDER BY id
            """.trimIndent(),
        ).query { rs, _ ->
            GenericReadAuditRow(
                actorAdminId = (rs.getObject("actor_admin_id") as? Number)?.toLong(),
                outcome = rs.getString("outcome"),
                targetType = rs.getString("target_type"),
                targetId = rs.getString("target_id"),
                reason = rs.getString("reason"),
                hasRevision = rs.getObject("revision_number") != null,
            )
        }.list()

        assertThat(audits).hasSize(9)
        assertThat(audits.map { it.reason?.substringBefore(' ') }).containsExactly(
            "route=ADMIN_ACCOUNT_LIST",
            "route=AUDIT_LOG_LIST",
            "route=TRASH_LIST",
            "route=CHILD_TRASH_LIST",
            "route=OPERATIONS_OVERVIEW",
            "route=SYSTEM_SETTINGS",
            "route=OBSERVABILITY_LINKS",
            "route=RESOURCE_DETAIL",
            "route=AUDIT_LOG_LIST",
        )
        assertThat(audits.take(7).map { it.outcome }).containsOnly("SUCCESS")
        assertThat(audits.takeLast(2).map { it.outcome }).containsOnly("FAILURE")
        assertThat(audits).allSatisfy { audit ->
            assertThat(audit.actorAdminId).isEqualTo(account.requiredId)
            assertThat(audit.reason).contains("adminSessionId=", "status=")
            assertThat(audit.hasRevision).isFalse()
        }
        assertThat(audits[7].targetType).isEqualTo("USER")
        assertThat(audits[7].targetId).isEqualTo("9223372036854775807")
        assertThat(audits.joinToString("|") { it.reason.orEmpty() })
            .doesNotContain("0123456789abcdef", "2026-08-27", "2026-08-26")
        assertThat(countEntityRevisions()).isEqualTo(revisionCountBeforeReads)
    }

    @Test
    fun `관리자 조회 감사 저장이 실패하면 성공 응답을 반환하지 않는다`() {
        val account = adminAccountFixture.관리자("read-audit-fail-closed")
        val changed = adminAuthService.changePassword(
            account.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "read-audit-fail-closed-password-456",
            ),
            "127.0.0.1",
        )
        installAuditInsertFailureTrigger()

        try {
            assertThatThrownBy {
                mockMvc.get("/internal/admin/v1/admins") {
                    cookie(Cookie(AdminSessionCookie.NAME, changed.rawSessionToken))
                }
            }.isInstanceOf(Exception::class.java)
        } finally {
            removeAuditInsertFailureTrigger()
        }
    }

    @Test
    fun `대리보기는 로그인 세션에 결속되고 헤더 없이도 쓰기를 차단하며 sanitized view와 상관 감사를 남긴다`() {
        val account = adminAccountFixture.관리자("impersonation-controller")
        val changed = adminAuthService.changePassword(
            account.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "impersonation-controller-password-456",
            ),
            "127.0.0.1",
        )
        val cookie = Cookie(AdminSessionCookie.NAME, changed.rawSessionToken)
        val mutationHeaders: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
        }

        val userResponse = mockMvc.post("/internal/admin/v1/resources/USER") {
            mutationHeaders()
            content = """
                {
                  "reason": "대리보기 테스트 사용자 생성",
                  "fields": {
                    "provider": "KAKAO",
                    "providerId": "impersonation-target",
                    "nickname": "대리보기 대상"
                  }
                }
            """.trimIndent()
        }.andExpect { status { isCreated() } }.andReturn().response
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val userId = mapper.readTree(userResponse.contentAsString).path("id").asLong()
        val studio = adminResourceService.create(
            account.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 대리보기 스튜디오 준비",
                mapOf(
                    "ownerUserId" to userId,
                    "name" to "대리보기 스튜디오",
                    "galleryUrl" to "impersonation-view",
                    "contact" to "02-123-4567",
                    "description" to "대리보기 소개",
                ),
            ),
            "127.0.0.1",
        )
        val gallery = adminResourceService.create(
            account.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 대리보기 갤러리 준비",
                mapOf("workspaceId" to studio.id, "title" to "대리보기 갤러리"),
            ),
            "127.0.0.1",
        )
        val photo = adminResourceService.create(
            account.requiredId,
            AdminResourceType.PHOTO,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 대리보기 사진 준비",
                mapOf(
                    "galleryId" to gallery.id,
                    "storageKey" to "galleries/${gallery.id}/impersonation-secret.jpg",
                    "originalFileName" to "private-original.jpg",
                    "contentType" to "image/jpeg",
                ),
            ),
            "127.0.0.1",
        )
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM photo_selections WHERE gallery_id = :galleryId")
                .param("galleryId", gallery.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
        val conceptFolderId = createConceptFolder(gallery.id)
        val collaboration = adminResourceService.create(
            account.requiredId,
            AdminResourceType.COLLABORATION,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 협업 준비",
                mapOf(
                    "galleryId" to gallery.id,
                    "conceptFolderId" to conceptFolderId,
                    "name" to "가족 의견",
                ),
            ),
            "127.0.0.1",
        )
        val retouch = adminResourceService.create(
            account.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 보정 준비",
                mapOf("galleryId" to gallery.id, "roundNo" to 1),
            ),
            "127.0.0.1",
        )
        assignPhotoToSession(collaboration.id, photo.id)
        val collabGuestId = jdbcClient.sql(
            """
            INSERT INTO collab_participants
                (collab_session_id, participant_type, guest_token, nickname, version, created_at, updated_at)
            VALUES (:sessionId, 'GUEST', 'must-not-leak-guest-token', '하객', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("sessionId", collaboration.id).query { rs, _ -> rs.getLong("id") }.single()
        jdbcClient.sql(
            """
            INSERT INTO collab_photo_comments
                (collab_session_id, photo_id, participant_id, content, version, created_at, updated_at)
            VALUES (:sessionId, :photoId, :guestId,
                    '확인 private@example.com token=must-not-leak', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("sessionId", collaboration.id).param("photoId", photo.id)
            .param("guestId", collabGuestId).update()

        val sessionResponse = mockMvc.post("/internal/admin/v1/impersonations") {
            mutationHeaders()
            content = """
                {
                  "targetType": "USER",
                  "targetId": $userId,
                  "reason": "고객 화면 상태 확인"
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.readOnly") { value(true) }
            jsonPath("$.view.profile.id") { value(userId) }
            jsonPath("$.viewer.userId") { value(userId) }
            jsonPath("$.viewer.accessRole") { value("SELF") }
            jsonPath("$.view.workspaces.length()") { value(2) }
            jsonPath("$.view.workspaces[0].workspaceType") { value("PERSONAL") }
            jsonPath("$.view.workspaces[1].workspaceId") { value(studio.id) }
            jsonPath("$.view.workspaces[1].workspaceType") { value("STUDIO") }
            jsonPath("$.view.workspaces[1].contact") { value("02-123-4567") }
            jsonPath("$.view.workspaces[1].description") { value("대리보기 소개") }
            jsonPath("$.view.galleries[0].workspaceId") { value(studio.id) }
            jsonPath("$.view.galleries[0].workspaceType") { value("STUDIO") }
            jsonPath("$.view.galleries[0].publicStatus") { value("DRAFT") }
            jsonPath("$.view.galleries[0].workflowStatus") { value("DRAFT") }
            jsonPath("$.view.galleries[0].stage") { value("UPLOAD") }
            jsonPath("$.blockedCapabilities") { isArray() }
            jsonPath("$.view.sections.photos[0].photoId") { value(photo.id) }
            jsonPath("$.view.sections.selections[0].galleryId") { value(gallery.id) }
            jsonPath("$.view.sections.collaborations[0].sessionId") { value(collaboration.id) }
            jsonPath("$.view.sections.comments[0].photoId") { value(photo.id) }
            jsonPath("$.view.sections.categories[0].conceptFolderId") { value(conceptFolderId) }
            jsonPath("$.view.sections.retouch[0].galleryId") { value(gallery.id) }
            jsonPath("$.view.sectionCounts.photos") { value(1) }
            jsonPath("$.view.sectionFields.photos") { isArray() }
        }.andReturn().response
        val sessionPayload = mapper.readTree(sessionResponse.contentAsString)
        val sessionId = sessionPayload.path("id").asText()
        assertThat(
            jdbcClient.sql(
                "SELECT source_address FROM admin_impersonation_sessions WHERE id = CAST(:id AS UUID)",
            ).param("id", sessionId).query { rs, _ -> rs.getString(1) }.list().single(),
        ).isNull()
        val startCorrelationId = sessionPayload.path("correlationId").asText()
        assertThat(startCorrelationId)
            .matches(REQUEST_TRACE_REGEX)
            .isEqualTo(sessionResponse.getHeader(HttpLoggingFilter.CORRELATION_ID_HEADER))
            .isNotEqualTo(sessionId)
        assertThat(sessionResponse.contentAsString)
            .doesNotContain(
                "providerId",
                "private@example.com",
                "must-not-leak",
                "storageKey",
                "impersonation-secret.jpg",
                "originalFile",
                "private-original.jpg",
                "collabUrl",
                "viewUrl",
                "resultUrl",
                "annotationUrl",
            )
            .contains("[REDACTED_EMAIL]", "[REDACTED]")

        val currentResponse = mockMvc.get("/internal/admin/v1/impersonations/current") {
            cookie(cookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.admin.username") { value("impersonation-controller") }
            jsonPath("$.view.profile.nickname") { value("대리보기 대상") }
        }.andReturn().response
        val currentCorrelationId = mapper.readTree(currentResponse.contentAsString).path("correlationId").asText()
        assertThat(currentCorrelationId)
            .matches(REQUEST_TRACE_REGEX)
            .isEqualTo(currentResponse.getHeader(HttpLoggingFilter.CORRELATION_ID_HEADER))
            .isNotEqualTo(sessionId)

        val blockedResponse = mockMvc.post("/internal/admin/v1/resources/USER") {
            mutationHeaders()
            content = """
                {
                  "reason": "차단되어야 하는 생성",
                  "fields": {
                    "provider": "KAKAO",
                    "providerId": "must-not-exist",
                    "nickname": "생성 금지"
                  }
                }
            """.trimIndent()
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ADMIN_403_2") }
        }.andReturn().response
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM users WHERE provider_id = 'must-not-exist'")
                .query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()
        val failedAudit = jdbcClient.sql(
            """
            SELECT outcome, actor_admin_id, target_type, target_id, target_label, reason, correlation_id
            FROM admin_audit_logs
            WHERE action = 'MUTATION_FAILED'
            """.trimIndent(),
        ).query { rs, _ ->
            listOf(
                rs.getString("outcome"),
                rs.getLong("actor_admin_id").toString(),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("target_label"),
                rs.getString("reason"),
                rs.getString("correlation_id"),
            )
        }.single()
        assertThat(failedAudit[0]).isEqualTo("FAILURE")
        assertThat(failedAudit[1]).isEqualTo(account.requiredId.toString())
        assertThat(failedAudit[2]).isEqualTo("USER")
        assertThat(failedAudit[3]).isNull()
        assertThat(failedAudit[4]).isEqualTo("USER")
        assertThat(failedAudit[5]).doesNotContain("must-not-exist", "생성 금지")
        assertThat(failedAudit[6])
            .matches(REQUEST_TRACE_REGEX)
            .isEqualTo(blockedResponse.getHeader(HttpLoggingFilter.CORRELATION_ID_HEADER))

        val secondSession = adminAuthService.login(
            AdminLoginRequest("impersonation-controller", "impersonation-controller-password-456"),
            "127.0.0.1",
        )
        mockMvc.post("/internal/admin/v1/resources/USER") {
            cookie(Cookie(AdminSessionCookie.NAME, secondSession.rawSessionToken))
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "reason": "다른 로그인 세션은 대리보기에 결속되지 않음",
                  "fields": {
                    "provider": "KAKAO",
                    "providerId": "second-session-write",
                    "nickname": "두 번째 세션"
                  }
                }
            """.trimIndent()
        }.andExpect { status { isCreated() } }

        val endResponse = mockMvc.delete("/internal/admin/v1/impersonations/current") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
        }.andExpect { status { isNoContent() } }.andReturn().response
        val endCorrelationId = requireNotNull(endResponse.getHeader(HttpLoggingFilter.CORRELATION_ID_HEADER))
        assertThat(endCorrelationId).matches(REQUEST_TRACE_REGEX).isNotEqualTo(sessionId)
        assertThat(setOf(startCorrelationId, currentCorrelationId, endCorrelationId)).hasSize(3)

        mockMvc.get("/internal/admin/v1/impersonations/$sessionId") {
            cookie(cookie)
        }.andExpect { status { isNotFound() } }

        mockMvc.get("/internal/admin/v1/audit-logs") {
            cookie(cookie)
            param("impersonationSessionId", sessionId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(3) }
            jsonPath("$.contents[0].action") { value("READ_ONLY_IMPERSONATION_ENDED") }
            jsonPath("$.contents[1].action") { value("READ_ONLY_IMPERSONATION_VIEWED") }
            jsonPath("$.contents[2].action") { value("READ_ONLY_IMPERSONATION_STARTED") }
            jsonPath("$.contents[0].impersonationSessionId") { value(sessionId) }
            jsonPath("$.contents[1].impersonationSessionId") { value(sessionId) }
            jsonPath("$.contents[2].impersonationSessionId") { value(sessionId) }
            jsonPath("$.contents[0].correlationId") { value(endCorrelationId) }
            jsonPath("$.contents[1].correlationId") { value(currentCorrelationId) }
            jsonPath("$.contents[2].correlationId") { value(startCorrelationId) }
        }
        mockMvc.get("/internal/admin/v1/audit-logs") {
            cookie(cookie)
            param("correlationId", startCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.contents[0].action") { value("READ_ONLY_IMPERSONATION_STARTED") }
            jsonPath("$.contents[0].impersonationSessionId") { value(sessionId) }
            jsonPath("$.contents[0].correlationId") { value(startCorrelationId) }
        }

        val logoutSessionResponse = mockMvc.post("/internal/admin/v1/impersonations") {
            mutationHeaders()
            content = """
                {
                  "targetType": "USER",
                  "targetId": $userId,
                  "reason": "로그아웃 종료 검증"
                }
            """.trimIndent()
        }.andExpect { status { isCreated() } }.andReturn().response
        val logoutSessionPayload = mapper.readTree(logoutSessionResponse.contentAsString)
        val logoutSessionId = logoutSessionPayload.path("id").asText()
        val logoutStartCorrelationId = logoutSessionPayload.path("correlationId").asText()
        assertThat(logoutStartCorrelationId)
            .matches(REQUEST_TRACE_REGEX)
            .isEqualTo(logoutSessionResponse.getHeader(HttpLoggingFilter.CORRELATION_ID_HEADER))
            .isNotEqualTo(logoutSessionId)

        val logoutResponse = mockMvc.post("/internal/admin/v1/auth/logout") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
        }.andExpect { status { isNoContent() } }.andReturn().response
        val logoutCorrelationId = requireNotNull(logoutResponse.getHeader(HttpLoggingFilter.CORRELATION_ID_HEADER))
        assertThat(logoutCorrelationId).matches(REQUEST_TRACE_REGEX).isNotEqualTo(logoutSessionId)
        assertThat(logoutCorrelationId).isNotEqualTo(logoutStartCorrelationId)
        mockMvc.get("/internal/admin/v1/auth/session") {
            cookie(cookie)
        }.andExpect { status { isUnauthorized() } }

        mockMvc.get("/internal/admin/v1/audit-logs") {
            cookie(Cookie(AdminSessionCookie.NAME, secondSession.rawSessionToken))
            param("impersonationSessionId", logoutSessionId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(2) }
            jsonPath("$.contents[0].action") { value("READ_ONLY_IMPERSONATION_ENDED") }
            jsonPath("$.contents[1].action") { value("READ_ONLY_IMPERSONATION_STARTED") }
            jsonPath("$.contents[0].impersonationSessionId") { value(logoutSessionId) }
            jsonPath("$.contents[1].impersonationSessionId") { value(logoutSessionId) }
            jsonPath("$.contents[0].correlationId") { value(logoutCorrelationId) }
            jsonPath("$.contents[1].correlationId") { value(logoutStartCorrelationId) }
        }
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM admin_impersonation_sessions WHERE id = CAST(:id AS UUID) AND ended_at IS NOT NULL")
                .param("id", logoutSessionId)
                .query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
    }

    @Test
    fun `스튜디오 구성원 대리보기는 실제 제품 권한을 따르고 탈퇴한 구성원은 거부한다`() {
        val account = adminAccountFixture.관리자("impersonation-studio-member")
        val changed = adminAuthService.changePassword(
            account.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "impersonation-studio-member-password-456",
            ),
            "127.0.0.1",
        )
        val cookie = Cookie(AdminSessionCookie.NAME, changed.rawSessionToken)
        val owner = adminResourceService.create(
            account.requiredId,
            AdminResourceType.USER,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 스튜디오 소유자 준비",
                mapOf("provider" to "GOOGLE", "providerId" to "impersonation-owner", "nickname" to "소유자"),
            ),
            "127.0.0.1",
        )
        val member = adminResourceService.create(
            account.requiredId,
            AdminResourceType.USER,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 스튜디오 구성원 준비",
                mapOf("provider" to "KAKAO", "providerId" to "impersonation-member", "nickname" to "구성원"),
            ),
            "127.0.0.1",
        )
        val studio = adminResourceService.create(
            account.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 구성원 대리보기 스튜디오 준비",
                mapOf("ownerUserId" to owner.id, "name" to "공동 운영 스튜디오", "galleryUrl" to "member-view"),
            ),
            "127.0.0.1",
        )
        val gallery = adminResourceService.create(
            account.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 구성원 대리보기 갤러리 준비",
                mapOf("workspaceId" to studio.id, "title" to "구성원 접근 갤러리"),
            ),
            "127.0.0.1",
        )
        val membershipId = jdbcClient.sql(
            """
            INSERT INTO workspace_members
                (workspace_id, user_id, role, version, created_at, updated_at)
            VALUES (:studioId, :userId, 'MEMBER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("studioId", studio.id).param("userId", member.id)
            .query { rs, _ -> rs.getLong("id") }.single()

        mockMvc.post("/internal/admin/v1/impersonations") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "targetType": "STUDIO",
                  "targetId": ${studio.id},
                  "viewerUserId": ${member.id},
                  "reason": "[TEST_OPERATION] 실제 구성원 화면 확인"
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.viewer.userId") { value(member.id) }
            jsonPath("$.viewer.accessRole") { value("WORKSPACE_MEMBER") }
            jsonPath("$.view.workspaces[0].workspaceId") { value(studio.id) }
            jsonPath("$.view.workspaces[0].workspaceType") { value("STUDIO") }
            jsonPath("$.view.galleries[0].id") { value(gallery.id) }
            jsonPath("$.view.galleries[0].workspaceId") { value(studio.id) }
            jsonPath("$.view.galleries[0].workspaceType") { value("STUDIO") }
            jsonPath("$.view.galleries[0].accessRole") { value("WORKSPACE_MEMBER") }
            jsonPath("$.view.sections.photos") { isArray() }
        }

        mockMvc.delete("/internal/admin/v1/impersonations/current") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
        }.andExpect { status { isNoContent() } }
        jdbcClient.sql("UPDATE workspace_members SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("id", membershipId).update()

        mockMvc.post("/internal/admin/v1/impersonations") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "targetType": "STUDIO",
                  "targetId": ${studio.id},
                  "viewerUserId": ${member.id},
                  "reason": "[TEST_OPERATION] 탈퇴 구성원 차단 확인"
                }
            """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("ADMIN_400_8") }
        }

        jdbcClient.sql(
            """
            INSERT INTO gallery_members (gallery_id, user_id, version, created_at, updated_at)
            VALUES (:galleryId, :userId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("galleryId", gallery.id).param("userId", member.id).update()

        mockMvc.post("/internal/admin/v1/impersonations") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "targetType": "USER",
                  "targetId": ${member.id},
                  "reason": "[TEST_OPERATION] DRAFT 멤버 목록 비노출 확인"
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.view.galleries") { isEmpty() }
        }
        mockMvc.delete("/internal/admin/v1/impersonations/current") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
        }.andExpect { status { isNoContent() } }

        mockMvc.post("/internal/admin/v1/impersonations") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "targetType": "GALLERY",
                  "targetId": ${gallery.id},
                  "viewerUserId": ${member.id},
                  "reason": "[TEST_OPERATION] DRAFT 직접 대리보기 차단 확인"
                }
            """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("ADMIN_400_8") }
        }
    }

    @Test
    fun `대리보기 자연 만료와 수동 종료는 각각 END 감사를 정확히 한 번 남긴다`() {
        val account = adminAccountFixture.관리자("impersonation-expiry")
        val changed = adminAuthService.changePassword(
            account.requiredId,
            ChangeAdminPasswordRequest(
                AdminAccountFixture.DEFAULT_PASSWORD,
                "impersonation-expiry-password-456",
            ),
            "127.0.0.1",
        )
        val cookie = Cookie(AdminSessionCookie.NAME, changed.rawSessionToken)
        val target = adminResourceService.create(
            account.requiredId,
            AdminResourceType.USER,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 만료 대리보기 대상",
                mapOf(
                    "provider" to "GOOGLE",
                    "providerId" to "impersonation-expiry-target",
                    "nickname" to "만료 대상",
                ),
            ),
            "127.0.0.1",
        )
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()

        fun start(): String {
            val response = mockMvc.post("/internal/admin/v1/impersonations") {
                cookie(cookie)
                header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                      "targetType": "USER",
                      "targetId": ${target.id},
                      "reason": "[INCIDENT_RECOVERY] 세션 종료 감사 검증"
                    }
                """.trimIndent()
            }.andExpect { status { isCreated() } }.andReturn().response
            return mapper.readTree(response.contentAsString).path("id").asText()
        }

        val expiredSessionId = start()
        jdbcClient.sql(
            "UPDATE admin_impersonation_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' " +
                "WHERE id = CAST(:id AS UUID)",
        ).param("id", expiredSessionId).update()

        val expiryResponse = mockMvc.get("/internal/admin/v1/impersonations/current") {
            cookie(cookie)
        }.andExpect { status { isNotFound() } }.andReturn().response
        val expiryCorrelationId = requireNotNull(
            expiryResponse.getHeader(HttpLoggingFilter.CORRELATION_ID_HEADER),
        )
        assertThat(expiryCorrelationId).matches(REQUEST_TRACE_REGEX)
        val expiredAudit = jdbcClient.sql(
            """
            SELECT COUNT(*) AS audit_count, MIN(reason) AS reason, MIN(correlation_id) AS correlation_id
            FROM admin_audit_logs
            WHERE action = 'READ_ONLY_IMPERSONATION_ENDED'
              AND impersonation_session_id = CAST(:id AS UUID)
            """.trimIndent(),
        ).param("id", expiredSessionId).query { rs, _ ->
            Triple(rs.getLong("audit_count"), rs.getString("reason"), rs.getString("correlation_id"))
        }.single()
        assertThat(expiredAudit.first).isOne()
        assertThat(expiredAudit.second).isEqualTo("category=READ_ONLY_IMPERSONATION_ENDED end=EXPIRED")
        assertThat(expiredAudit.third).isEqualTo(expiryCorrelationId)

        mockMvc.get("/internal/admin/v1/impersonations/current") {
            cookie(cookie)
        }.andExpect { status { isNotFound() } }
        assertThat(impersonationEndAuditCount(expiredSessionId)).isOne()

        val manualSessionId = start()
        mockMvc.delete("/internal/admin/v1/impersonations/current") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
        }.andExpect { status { isNoContent() } }
        mockMvc.delete("/internal/admin/v1/impersonations/current") {
            cookie(cookie)
            header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
        }.andExpect { status { isNotFound() } }
        assertThat(impersonationEndAuditCount(manualSessionId)).isOne()
    }

    private fun createConceptFolder(galleryId: Long): Long = jdbcClient.sql(
        """
        INSERT INTO concept_folders
            (gallery_id, name, sort_order, created_source, version, created_at, updated_at)
        VALUES (:galleryId, '대리보기 컨셉', 0, 'USER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    ).param("galleryId", galleryId).query { rs, _ -> rs.getLong("id") }.single()

    private fun assignPhotoToSession(sessionId: Long, photoId: Long) {
        val detailId = jdbcClient.sql(
            """
            INSERT INTO detail_folders
                (gallery_id, concept_folder_id, name, sort_order, created_source, version, created_at, updated_at)
            SELECT gallery_id, concept_folder_id, '대리보기 상세', 0, 'USER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM collab_sessions WHERE id = :sessionId
            RETURNING id
            """.trimIndent(),
        ).param("sessionId", sessionId).query { rs, _ -> rs.getLong("id") }.single()
        jdbcClient.sql(
            """
            INSERT INTO photo_category_assignments
                (gallery_id, photo_id, detail_folder_id, assigned_source, assigned_at, version, created_at, updated_at)
            SELECT gallery_id, id, :detailId, 'USER', CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM photos WHERE id = :photoId
            """.trimIndent(),
        ).param("photoId", photoId).param("detailId", detailId).update()
    }

    companion object {
        private const val REQUEST_TRACE_REGEX = "[0-9a-f]{16}"
    }

    private fun countEntityRevisions(): Long = jdbcClient.sql("SELECT COUNT(*) FROM admin_entity_revisions")
        .query { rs, _ -> rs.getLong(1) }
        .single()

    private fun impersonationEndAuditCount(sessionId: String): Long = jdbcClient.sql(
        """
        SELECT COUNT(*)
        FROM admin_audit_logs
        WHERE action = 'READ_ONLY_IMPERSONATION_ENDED'
          AND impersonation_session_id = CAST(:id AS UUID)
        """.trimIndent(),
    ).param("id", sessionId).query { rs, _ -> rs.getLong(1) }.single()

    private fun installAuditInsertFailureTrigger() {
        jdbcClient.sql(
            """
            CREATE OR REPLACE FUNCTION fail_admin_read_audit_insert()
            RETURNS TRIGGER
            LANGUAGE plpgsql
            AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'forced read audit failure';
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbcClient.sql(
            """
            CREATE TRIGGER trg_fail_admin_read_audit_insert
            BEFORE INSERT ON admin_audit_logs
            FOR EACH ROW
            EXECUTE FUNCTION fail_admin_read_audit_insert()
            """.trimIndent(),
        ).update()
    }

    private fun removeAuditInsertFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS trg_fail_admin_read_audit_insert ON admin_audit_logs").update()
        jdbcClient.sql("DROP FUNCTION IF EXISTS fail_admin_read_audit_insert()").update()
    }

    private data class ResourceReadAuditRow(
        val actorAdminId: Long,
        val actorUsername: String?,
        val targetType: String?,
        val targetId: String?,
        val targetLabel: String?,
        val sourceAddress: String?,
        val reason: String?,
        val changedFields: String?,
        val hasRevision: Boolean,
        val correlationId: String?,
    )

    private data class GenericReadAuditRow(
        val actorAdminId: Long?,
        val outcome: String,
        val targetType: String?,
        val targetId: String?,
        val reason: String?,
        val hasRevision: Boolean,
    )
}
