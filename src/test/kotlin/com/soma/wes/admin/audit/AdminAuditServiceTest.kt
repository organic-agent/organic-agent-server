package com.soma.wes.admin.audit

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.audit.repository.AdminEntityRevisionRepository
import com.soma.wes.admin.audit.service.AdminAuditQueryService
import com.soma.wes.admin.audit.service.AdminRevisionRestoreService
import com.soma.wes.admin.audit.dto.request.AdminRevisionRestoreRequest
import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import com.soma.wes.admin.audit.support.AdminMutationAuditContext
import com.soma.wes.admin.audit.support.AdminRevisionPurgeScheduler
import com.soma.wes.admin.domain.AdminAccountStatus
import com.soma.wes.admin.dto.request.ChangeAdminStatusRequest
import com.soma.wes.admin.dto.request.CreateAdminAccountRequest
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.service.AdminAccountService
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@IntegrationTest
class AdminAuditServiceTest @Autowired constructor(
    private val adminAccountService: AdminAccountService,
    private val adminAccountRepository: AdminAccountRepository,
    private val auditLogRepository: AdminAuditLogRepository,
    private val revisionRepository: AdminEntityRevisionRepository,
    private val queryService: AdminAuditQueryService,
    private val restoreService: AdminRevisionRestoreService,
    private val purgeScheduler: AdminRevisionPurgeScheduler,
    private val snapshotCodec: AdminAuditSnapshotCodec,
    private val adminAccountFixture: AdminAccountFixture,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionManager: PlatformTransactionManager,
) {

    @Test
    fun `계정 변경과 감사 로그 및 7일 리비전을 같은 트랜잭션에 저장한다`() {
        val actor = adminAccountFixture.관리자("audit-actor")
        val target = adminAccountFixture.관리자("audit-target")

        adminAccountService.changeStatus(
            actorAdminId = actor.requiredId,
            targetAdminId = target.requiredId,
            request = ChangeAdminStatusRequest(AdminAccountStatus.SUSPENDED, "업무 담당 종료"),
            sourceAddress = "127.0.0.1",
        )

        val audit = auditLogRepository.findAll().single()
        assertThat(audit.action).isEqualTo(AdminAuditAction.ACCOUNT_SUSPENDED)
        assertThat(audit.actorAdminId).isEqualTo(actor.requiredId)
        assertThat(audit.changedFields).containsExactly("status")

        val detail = queryService.getDetail(audit.requiredId)
        assertThat(detail.revision).isNotNull
        val revision = requireNotNull(detail.revision)
        assertThat(revision.before!!.get("status").stringValue()).isEqualTo("ACTIVE")
        assertThat(revision.after!!.get("status").stringValue()).isEqualTo("SUSPENDED")
        assertThat(revision.before.toString()).doesNotContain("password", "token", "secret")
    }

    @Test
    fun `성공 감사의 실제 transaction commit 뒤에만 HTTP 변경 완료 표식을 남긴다`() {
        val actor = adminAccountFixture.관리자("audit-commit-context-actor")
        val target = adminAccountFixture.관리자("audit-commit-context-target")
        val request = MockHttpServletRequest()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        try {
            adminAccountService.changeStatus(
                actorAdminId = actor.requiredId,
                targetAdminId = target.requiredId,
                request = ChangeAdminStatusRequest(AdminAccountStatus.SUSPENDED, "commit 경계 검증"),
                sourceAddress = "127.0.0.1",
            )

            assertThat(request.getAttribute(AdminMutationAuditContext.MUTATION_COMMITTED_ATTRIBUTE)).isEqualTo(true)
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }

    @Test
    fun `감사 로그 저장이 실패하면 실제 계정 변경과 리비전도 롤백한다`() {
        val actor = adminAccountFixture.관리자("rollback-actor")
        val target = adminAccountFixture.관리자("rollback-target")
        installAuditInsertFailureTrigger()

        try {
            assertThatThrownBy {
                adminAccountService.changeStatus(
                    actorAdminId = actor.requiredId,
                    targetAdminId = target.requiredId,
                    request = ChangeAdminStatusRequest(AdminAccountStatus.SUSPENDED, "롤백 검증"),
                    sourceAddress = "127.0.0.1",
                )
            }
            assertThat(adminAccountRepository.findById(target.requiredId).orElseThrow().status)
                .isEqualTo(AdminAccountStatus.ACTIVE)
            assertThat(revisionRepository.count()).isZero()
        } finally {
            removeAuditInsertFailureTrigger()
        }
    }

    @Test
    fun `7일 리비전으로 계정 상태를 복원하고 복원 자체도 새 리비전으로 남긴다`() {
        val actor = adminAccountFixture.관리자("restore-actor")
        val created = adminAccountService.create(
            actorAdminId = actor.requiredId,
            request = CreateAdminAccountRequest("restore-target", "복원 대상", "운영 담당자 추가"),
            sourceAddress = "127.0.0.1",
        )
        adminAccountService.changeStatus(
            actorAdminId = actor.requiredId,
            targetAdminId = created.account.id,
            request = ChangeAdminStatusRequest(AdminAccountStatus.SUSPENDED, "일시 정지"),
            sourceAddress = "127.0.0.1",
        )

        val activeRevision = revisionRepository
            .findAllByTargetTypeAndTargetIdOrderByRevisionNumberDesc(
                AdminAuditTargetType.ADMIN_ACCOUNT,
                created.account.id.toString(),
            )
            .last()

        val restoreAuditId = restoreService.restore(
            actorAdminId = actor.requiredId,
            revisionId = activeRevision.requiredId,
            request = AdminRevisionRestoreRequest(
                reason = "정상 상태로 복원",
                expectedVersion = adminAccountRepository.findById(created.account.id).orElseThrow().version,
            ),
            sourceAddress = "127.0.0.1",
        )

        val restored = adminAccountRepository.findById(created.account.id).orElseThrow()
        assertThat(restored.status).isEqualTo(AdminAccountStatus.ACTIVE)
        assertThat(queryService.getDetail(restoreAuditId).audit.action)
            .isEqualTo(AdminAuditAction.REVISION_RESTORED)
        assertThat(revisionRepository.findAllByTargetTypeAndTargetIdOrderByRevisionNumberDesc(
            AdminAuditTargetType.ADMIN_ACCOUNT,
            created.account.id.toString(),
        )).hasSize(3)
    }

    @Test
    fun `7일 후 복원 payload만 지우고 sanitized 리비전 증거와 감사 로그는 영구 보존한다`() {
        val actor = adminAccountFixture.관리자("purge-actor")
        val created = adminAccountService.create(
            actorAdminId = actor.requiredId,
            request = CreateAdminAccountRequest("purge-target", "정리 대상", "운영 담당자 추가"),
            sourceAddress = "127.0.0.1",
        )
        val original = revisionRepository.findAll().single()
        val permanentBefore = original.beforeSnapshot
        val permanentAfter = original.afterSnapshot
        assertThat(original.afterRestorePayload).isNotNull()
        jdbcTemplate.update(
            """
            UPDATE admin_entity_revisions
            SET expires_at = NOW() - INTERVAL '1 second',
                restore_expires_at = NOW() - INTERVAL '1 second'
            """.trimIndent(),
        )

        assertThat(purgeScheduler.purgeExpiredRevisions()).isEqualTo(1)
        assertThat(revisionRepository.count()).isOne()
        val retained = revisionRepository.findAll().single()
        assertThat(retained.beforeSnapshot).isEqualTo(permanentBefore)
        assertThat(retained.afterSnapshot).isEqualTo(permanentAfter)
        assertThat(retained.beforeRestorePayload).isNull()
        assertThat(retained.afterRestorePayload).isNull()
        assertThat(auditLogRepository.count()).isEqualTo(1)
        val detail = queryService.getDetail(auditLogRepository.findAll().single().requiredId)
        assertThat(detail.revision).isNotNull
        assertThat(detail.revision!!.restorable).isFalse()
        val retainedResponse = queryService.getRevisions(
            AdminAuditTargetType.ADMIN_ACCOUNT,
            created.account.id.toString(),
        ).single()
        assertThat(retainedResponse.before).isNull()
        assertThat(retainedResponse.after).isNotNull()
        assertThat(retainedResponse.restorable).isFalse()
    }

    @Test
    fun `영구 감사 로그는 데이터베이스에서도 수정할 수 없다`() {
        val actor = adminAccountFixture.관리자("immutable-actor")
        adminAccountService.create(
            actorAdminId = actor.requiredId,
            request = CreateAdminAccountRequest("immutable-target", "불변 대상", "운영 담당자 추가"),
            sourceAddress = "127.0.0.1",
        )

        assertThatThrownBy {
            jdbcTemplate.update("UPDATE admin_audit_logs SET reason = 'tampered'")
        }
        assertThat(auditLogRepository.findAll().single().reason)
            .isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=true")
    }

    @Test
    fun `스냅샷 코덱은 allowlist 밖의 문자열과 중첩된 비밀번호 토큰을 마스킹한다`() {
        val encoded = snapshotCodec.encode(
            mapOf(
                "safe" to "visible",
                "passwordHash" to "argon-secret",
                "nested" to mapOf("oauthToken" to "oauth-secret"),
                "freeText" to "private@example.com Bearer live-access-token",
            ),
        )

        assertThat(encoded).isEqualTo("{}")
        assertThat(encoded).doesNotContain("argon-secret", "oauth-secret")
            .doesNotContain("private@example.com", "live-access-token")
    }

    @Test
    fun `영구 스냅샷과 changed fields는 exact key와 엄격한 timestamp만 허용한다`() {
        val encoded = snapshotCodec.encode(
            linkedMapOf(
                "createdAt" to "2026-08-27T13:10:00Z",
                "deletedAt" to "2026-08-27T22:10:00+09:00[Asia/Seoul]",
                "updatedAt" to "2026-99-99T99:99:99+99:99",
                "accountId" to 99887766,
                "private@example.com" to "ACTIVE",
                "fields" to mapOf(
                    "status" to "ACTIVE",
                    "customerName-private@example.com" to "OPEN",
                ),
            ),
        )

        assertThat(encoded)
            .contains(
                "\"createdAt\":\"2026-08-27T13:10:00Z\"",
                "\"deletedAt\":\"2026-08-27T22:10:00+09:00[Asia/Seoul]\"",
                "\"updatedAt\":\"[REDACTED]\"",
                "\"status\":\"ACTIVE\"",
            )
            .doesNotContain("accountId", "private@example.com", "customerName")
        assertThat(
            snapshotCodec.sanitizeChangedFields(
                listOf("status", "private@example.com", "accountId", "label", "status"),
            ),
        ).containsExactly("status")
        assertThat(
            snapshotCodec.changedFields(
                mapOf("status" to "DRAFT", "private@example.com" to "before"),
                mapOf("status" to "OPEN", "private@example.com" to "after"),
            ),
        ).containsExactly("status")
    }

    @Test
    fun `과거에 저장된 영구 스냅샷도 조회할 때 현재 allowlist로 다시 마스킹한다`() {
        val decoded = snapshotCodec.decodePermanent(
            """
            {
              "type":"USER",
              "id":17,
              "version":3,
              "email":"legacy-private@example.com",
              "label":"홍길동",
              "fields":{"status":"ACTIVE","accountNumber":1234567890,"content":"과거 원문"}
            }
            """.trimIndent(),
        )

        assertThat(decoded.toString())
            .contains("USER", "17", "version", "3", "ACTIVE", "[REDACTED]")
            .doesNotContain("legacy-private@example.com", "홍길동", "1234567890", "과거 원문")
    }

    @Test
    fun `과거 감사 행의 key value 위장 PII와 IP 대상 라벨도 조회 응답에서 정규화한다`() {
        val actor = adminAccountFixture.관리자("legacy-audit-reader")
        val target = adminAccountFixture.관리자("legacy-audit-target")
        jdbcTemplate.update(
            """
            INSERT INTO admin_audit_logs
                (action, outcome, actor_admin_id, actor_username_snapshot, target_type, target_id,
                 target_label, source_address, reason, changed_fields, created_at)
            VALUES
                ('ACCOUNT_SUSPENDED', 'SUCCESS', ?, ?, 'ADMIN_ACCOUNT', ?, ?, ?, ?,
                 'status,private@example.com,accountId,label', CURRENT_TIMESTAMP)
            """.trimIndent(),
            actor.requiredId,
            actor.username,
            target.requiredId.toString(),
            "홍길동 private@example.com",
            "203.0.113.41",
            "note=Alice customerName=Hong memo=01012345678",
        )

        val response = queryService.search(
            actorAdminId = actor.requiredId,
            actorUsername = null,
            targetType = AdminAuditTargetType.ADMIN_ACCOUNT,
            targetId = target.requiredId.toString(),
            action = AdminAuditAction.ACCOUNT_SUSPENDED,
            outcome = null,
            correlationId = null,
            impersonationSessionId = null,
            from = null,
            to = null,
            page = 0,
            size = 20,
        ).contents.single()

        assertThat(response.targetLabel).isEqualTo("ADMIN_ACCOUNT #${target.requiredId}")
        assertThat(response.sourceAddress).isNull()
        assertThat(response.reason).isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=true")
        assertThat(response.changedFields).containsExactly("status")
    }

    @Test
    fun `V40은 과거 영구 감사와 휴지통 원문을 exact key로 비가역 정리한다`() {
        val actor = adminAccountFixture.관리자("legacy-redaction-actor")
        val beforeSnapshot =
            """{"type":"GALLERY","id":987654,"version":2,"title":"이전 본식 제목","status":"DRAFT","deleted":false,"createdAt":"2026-08-27T13:10:00Z","updatedAt":"2026-99-99T99:99:99+99:99","private@example.com":"ACTIVE","fields":{"status":"DRAFT","customerName-private@example.com":"OPEN"}}"""
        val afterSnapshot =
            """{"type":"GALLERY","id":987654,"version":3,"label":"홍길동 본식","title":"private@example.com 본식 password=top-secret","status":"OPEN","deleted":false,"content":"삭제된 고객 본문","accountNumber":1234567890}"""
        jdbcTemplate.update(
            """
            INSERT INTO admin_audit_logs
                (action, outcome, actor_admin_id, actor_username_snapshot, target_type, target_id,
                 target_label, source_address, reason, changed_fields, revision_number, created_at)
            VALUES
                ('RESOURCE_UPDATED', 'SUCCESS', ?, '홍길동 운영자', 'GALLERY', '987654',
                 'private@example.com 본식 제목', '203.0.113.41',
                 '[DATA_CORRECTION] 고객 이름과 제목 정정',
                 'status,title, updatedAt,private@example.com,accountId,label,status', 100, CURRENT_TIMESTAMP),
                ('RESOURCE_UPDATED', 'SUCCESS', ?, 'Alice', 'GALLERY', '987656',
                 'Hong wedding', '203.0.113.44',
                 'note=Alice customerName=Hong memo=01012345678', NULL, NULL, CURRENT_TIMESTAMP)
            """.trimIndent(),
            actor.requiredId,
            actor.requiredId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO admin_auth_events
                (event_type, actor_admin_id, target_admin_id, username_snapshot,
                 source_address, reason, successful, created_at)
            VALUES
                ('ACCOUNT_STATUS_CHANGED', ?, ?, '홍길동 운영자', '203.0.113.42',
                 '[SECURITY_RESPONSE] 개인정보 노출 대응', TRUE, CURRENT_TIMESTAMP),
                ('LOGIN_FAILED', NULL, NULL, 'unknown-private@example.com', '203.0.113.43',
                 NULL, FALSE, CURRENT_TIMESTAMP)
            """.trimIndent(),
            actor.requiredId,
            actor.requiredId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO admin_trash_batches
                (root_type, root_id, root_label, actor_admin_id, actor_username, reason, status,
                 deleted_at, restore_until, created_at, updated_at)
            VALUES
                ('GALLERY', 88001, 'private@example.com wedding', ?, '홍길동',
                 '[CUSTOMER_REQUEST] 고객 원문', 'ACTIVE', CURRENT_TIMESTAMP,
                 CURRENT_TIMESTAMP + INTERVAL '7 days', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                ('PHOTO', 88002, 'Alice photo', ?, 'Alice', 'private@example.com', 'PURGED',
                 CURRENT_TIMESTAMP - INTERVAL '8 days', CURRENT_TIMESTAMP - INTERVAL '1 day',
                 CURRENT_TIMESTAMP - INTERVAL '8 days', CURRENT_TIMESTAMP)
            """.trimIndent(),
            actor.requiredId,
            actor.requiredId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO admin_child_trash_records
                (resource_type, resource_id, parent_type, parent_id, actor_admin_id, actor_username,
                 reason, status, deleted_at, restore_until, created_at, updated_at)
            VALUES
                ('COLLAB_COMMENT', 88101, 'COLLABORATION', 88100, ?, '홍길동',
                 '[DATA_CORRECTION] 댓글 원문', 'ACTIVE', CURRENT_TIMESTAMP,
                 CURRENT_TIMESTAMP + INTERVAL '7 days', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                ('COLLAB_COMMENT', 88102, 'COLLABORATION', 88100, ?, 'Alice',
                 'private@example.com', 'PURGED', CURRENT_TIMESTAMP - INTERVAL '8 days',
                 CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '8 days',
                 CURRENT_TIMESTAMP)
            """.trimIndent(),
            actor.requiredId,
            actor.requiredId,
        )

        val migration = ClassPathResource(
            "db/migration/V40__harden_permanent_audit_invariants.sql",
        ).inputStream.bufferedReader().use { it.readText() }
        TransactionTemplate(transactionManager).executeWithoutResult {
            jdbcTemplate.execute(
                "ALTER TABLE admin_entity_revisions " +
                    "DROP CONSTRAINT IF EXISTS ck_admin_entity_revisions_restore_window",
            )
            jdbcTemplate.update(
                """
                INSERT INTO admin_entity_revisions
                    (target_type, target_id, revision_number, operation, before_snapshot, after_snapshot,
                     restore_expires_at, snapshot_schema_version, target_version,
                     before_restore_payload, after_restore_payload, created_at)
                VALUES
                    ('GALLERY', '987654', 100, 'RESOURCE_UPDATED', ?, ?,
                     CURRENT_TIMESTAMP + INTERVAL '8 days', 1, NULL, ?, ?, CURRENT_TIMESTAMP),
                    ('GALLERY', '987655', 101, 'RESOURCE_UPDATED', ?, ?,
                     CURRENT_TIMESTAMP - INTERVAL '1 second', 1, NULL, ?, ?, CURRENT_TIMESTAMP)
                """.trimIndent(),
                beforeSnapshot,
                afterSnapshot,
                beforeSnapshot,
                afterSnapshot,
                beforeSnapshot.replace("987654", "987655"),
                afterSnapshot.replace("987654", "987655"),
                beforeSnapshot.replace("987654", "987655"),
                afterSnapshot.replace("987654", "987655"),
            )
            jdbcTemplate.execute(migration)
        }

        val permanent = jdbcTemplate.queryForMap(
            """
            SELECT target_id, target_version, snapshot_schema_version,
                   before_snapshot, after_snapshot, before_restore_payload, after_restore_payload,
                   restore_expires_at = created_at + INTERVAL '7 days' AS restore_window_clamped,
                   expires_at = restore_expires_at AS legacy_expiry_synchronized
            FROM admin_entity_revisions
            WHERE target_type = 'GALLERY' AND revision_number = 100
            """.trimIndent(),
        )
        val permanentText = permanent["before_snapshot"].toString() + permanent["after_snapshot"].toString()
        assertThat(permanent["target_id"]).isEqualTo("987654")
        assertThat(permanent["target_version"]).isEqualTo(3L)
        assertThat(permanent["snapshot_schema_version"]).isEqualTo(3)
        assertThat(permanent["restore_window_clamped"]).isEqualTo(true)
        assertThat(permanent["legacy_expiry_synchronized"]).isEqualTo(true)
        val permanentBefore = requireNotNull(snapshotCodec.decode(permanent["before_snapshot"] as String))
        assertThat(permanentBefore.get("createdAt").stringValue()).isEqualTo("2026-08-27T13:10:00Z")
        assertThat(permanentBefore.get("updatedAt").stringValue()).isEqualTo("[REDACTED]")
        assertThat(permanentText)
            .contains(
                "GALLERY",
                "987654",
                "version",
                "status",
                "DRAFT",
                "OPEN",
                "[REDACTED]",
                "createdAt",
                "2026-08-27T13:10:00Z",
                "updatedAt",
            )
            .doesNotContain(
                "이전 본식 제목",
                "홍길동 본식",
                "private@example.com",
                "top-secret",
                "삭제된 고객 본문",
                "1234567890",
                "private@example.com\":\"ACTIVE",
                "customerName-private@example.com",
                "accountId",
            )
        val restoreText = permanent["before_restore_payload"].toString() +
            permanent["after_restore_payload"].toString()
        assertThat(restoreText)
            .contains("이전 본식 제목", "private@example.com 본식", "password=[REDACTED]")
            .doesNotContain("top-secret", "삭제된 고객 본문", "accountNumber", "label")

        val expiredPayloads = jdbcTemplate.queryForMap(
            """
            SELECT before_restore_payload, after_restore_payload
            FROM admin_entity_revisions
            WHERE target_type = 'GALLERY' AND revision_number = 101
            """.trimIndent(),
        )
        assertThat(expiredPayloads["before_restore_payload"]).isNull()
        assertThat(expiredPayloads["after_restore_payload"]).isNull()

        val audit = jdbcTemplate.queryForMap(
            """
            SELECT actor_username_snapshot, target_id, target_label, source_address, reason, changed_fields
            FROM admin_audit_logs
            WHERE target_type = 'GALLERY' AND target_id = '987654'
            """.trimIndent(),
        )
        assertThat(audit["actor_username_snapshot"]).isEqualTo("ADMIN #${actor.requiredId}")
        assertThat(audit["target_label"]).isEqualTo("GALLERY #987654")
        assertThat(audit["source_address"]).isNull()
        assertThat(audit["reason"])
            .isEqualTo("reasonCategory=DATA_CORRECTION operatorReasonProvided=true")
        assertThat(audit["changed_fields"]).isEqualTo("status,title")

        val disguisedPiiAudit = jdbcTemplate.queryForMap(
            """
            SELECT actor_username_snapshot, target_label, source_address, reason
            FROM admin_audit_logs
            WHERE target_type = 'GALLERY' AND target_id = '987656'
            """.trimIndent(),
        )
        assertThat(disguisedPiiAudit["actor_username_snapshot"]).isEqualTo("ADMIN #${actor.requiredId}")
        assertThat(disguisedPiiAudit["target_label"]).isEqualTo("GALLERY #987656")
        assertThat(disguisedPiiAudit["source_address"]).isNull()
        assertThat(disguisedPiiAudit["reason"])
            .isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=true")

        val authRows = jdbcTemplate.queryForList(
            """
            SELECT username_snapshot, source_address, reason
            FROM admin_auth_events
            ORDER BY id
            """.trimIndent(),
        )
        assertThat(authRows[0]["username_snapshot"]).isEqualTo("ADMIN #${actor.requiredId}")
        assertThat(authRows[0]["source_address"]).isNull()
        assertThat(authRows[0]["reason"])
            .isEqualTo("reasonCategory=SECURITY_RESPONSE operatorReasonProvided=true")
        assertThat(authRows[1]["username_snapshot"]).isNull()
        assertThat(authRows[1]["source_address"]).isNull()
        assertThat(authRows[1]["reason"])
            .isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=false")

        val batches = jdbcTemplate.queryForList(
            """
            SELECT root_id, root_label, actor_username, reason
            FROM admin_trash_batches
            WHERE root_id IN (88001, 88002)
            ORDER BY root_id
            """.trimIndent(),
        )
        assertThat(batches[0]["root_label"]).isEqualTo("GALLERY #88001")
        assertThat(batches[0]["actor_username"]).isEqualTo("ADMIN #${actor.requiredId}")
        assertThat(batches[0]["reason"])
            .isEqualTo("reasonCategory=CUSTOMER_REQUEST operatorReasonProvided=true")
        assertThat(batches[1]["root_label"]).isEqualTo("PHOTO #88002")
        assertThat(batches[1]["actor_username"]).isNull()
        assertThat(batches[1]["reason"])
            .isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=false")

        val children = jdbcTemplate.queryForList(
            """
            SELECT resource_id, actor_username, reason
            FROM admin_child_trash_records
            WHERE resource_id IN (88101, 88102)
            ORDER BY resource_id
            """.trimIndent(),
        )
        assertThat(children[0]["actor_username"]).isEqualTo("ADMIN #${actor.requiredId}")
        assertThat(children[0]["reason"])
            .isEqualTo("reasonCategory=DATA_CORRECTION operatorReasonProvided=true")
        assertThat(children[1]["actor_username"]).isNull()
        assertThat(children[1]["reason"])
            .isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=false")

        assertThatThrownBy {
            jdbcTemplate.update("UPDATE admin_audit_logs SET reason = 'tampered'")
        }
    }

    @Test
    fun `영구 리비전은 payload 소거와 만료 단축 외 UPDATE 및 DELETE를 거부한다`() {
        val actor = adminAccountFixture.관리자("revision-db-guard-actor")
        adminAccountService.create(
            actorAdminId = actor.requiredId,
            request = CreateAdminAccountRequest("revision-db-guard-target", "불변 대상", "테스트 생성"),
            sourceAddress = "127.0.0.1",
        )
        val revisionId = revisionRepository.findAll().single().requiredId

        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE admin_entity_revisions SET after_snapshot = '{}' WHERE id = ?",
                revisionId,
            )
        }.hasMessageContaining("immutable")
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                UPDATE admin_entity_revisions
                SET restore_expires_at = restore_expires_at + INTERVAL '1 second'
                WHERE id = ?
                """.trimIndent(),
                revisionId,
            )
        }.hasMessageContaining("may only be shortened")
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE admin_entity_revisions SET after_restore_payload = '{}' WHERE id = ?",
                revisionId,
            )
        }.hasMessageContaining("may only be cleared")
        assertThatThrownBy {
            jdbcTemplate.update("DELETE FROM admin_entity_revisions WHERE id = ?", revisionId)
        }.hasMessageContaining("immutable")

        assertThat(
            jdbcTemplate.update(
                """
                UPDATE admin_entity_revisions
                SET expires_at = expires_at - INTERVAL '1 minute',
                    restore_expires_at = restore_expires_at - INTERVAL '1 minute',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.trimIndent(),
                revisionId,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.update(
                """
                UPDATE admin_entity_revisions
                SET before_restore_payload = NULL, after_restore_payload = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.trimIndent(),
                revisionId,
            ),
        ).isEqualTo(1)
        assertThatThrownBy {
            jdbcTemplate.update(
                "UPDATE admin_entity_revisions SET after_restore_payload = '{}' WHERE id = ?",
                revisionId,
            )
        }.hasMessageContaining("may only be cleared")
    }

    @Test
    fun `리비전 restore window는 INSERT에서도 생성 후 7일을 넘을 수 없다`() {
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO admin_entity_revisions
                    (target_type, target_id, revision_number, operation, restore_expires_at,
                     snapshot_schema_version, created_at)
                VALUES
                    ('USER', '99001', 1, 'RESOURCE_UPDATED', CURRENT_TIMESTAMP + INTERVAL '8 days',
                     3, CURRENT_TIMESTAMP)
                """.trimIndent(),
            )
        }.hasMessageContaining("ck_admin_entity_revisions_restore_window")
    }

    @Test
    fun `전환기 구형과 신형 INSERT는 두 expiry 컬럼을 같은 값으로 채운다`() {
        jdbcTemplate.update(
            """
            INSERT INTO admin_entity_revisions
                (target_type, target_id, revision_number, operation, expires_at,
                 snapshot_schema_version, created_at)
            VALUES
                ('USER', '99002', 1, 'RESOURCE_UPDATED', CURRENT_TIMESTAMP + INTERVAL '1 day',
                 3, CURRENT_TIMESTAMP)
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO admin_entity_revisions
                (target_type, target_id, revision_number, operation, restore_expires_at,
                 snapshot_schema_version, created_at)
            VALUES
                ('USER', '99003', 1, 'RESOURCE_UPDATED', CURRENT_TIMESTAMP + INTERVAL '1 day',
                 3, CURRENT_TIMESTAMP)
            """.trimIndent(),
        )

        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM admin_entity_revisions
                WHERE target_id IN ('99002', '99003')
                  AND expires_at = restore_expires_at
                """.trimIndent(),
                Long::class.java,
            ),
        ).isEqualTo(2L)

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO admin_entity_revisions
                    (target_type, target_id, revision_number, operation, expires_at,
                     restore_expires_at, snapshot_schema_version, created_at)
                VALUES
                    ('USER', '99004', 1, 'RESOURCE_UPDATED', CURRENT_TIMESTAMP + INTERVAL '1 day',
                     CURRENT_TIMESTAMP + INTERVAL '2 days', 3, CURRENT_TIMESTAMP)
                """.trimIndent(),
            )
        }.hasMessageContaining("ck_admin_entity_revisions_expiry_columns_match")
    }

    @Test
    fun `영구 스냅샷은 중첩 fields sections에서도 구조 증거 외 문자열과 수치를 남기지 않는다`() {
        val encoded = snapshotCodec.encode(
            mapOf(
                "type" to "GALLERY",
                "id" to 17,
                "version" to 4,
                "label" to "김민수 이영희 본식",
                "title" to "서울 강남 웨딩",
                "deleted" to false,
                "fields" to mapOf(
                    "status" to "OPEN",
                    "name" to "고객 실명",
                    "score" to 0.987,
                    "accountNumber" to 1234567890L,
                    "residentNumber" to 9001011234567L,
                    "galleryId" to 17,
                ),
                "sections" to mapOf(
                    "comments" to listOf(
                        mapOf(
                            "commentId" to 99,
                            "content" to "삭제된 댓글 원문",
                            "author" to "private@example.com",
                            "status" to "ACTIVE",
                        ),
                    ),
                ),
            ),
        )

        assertThat(encoded)
            .contains("GALLERY", "17", "version", "4", "OPEN", "commentId", "99", "ACTIVE")
            .doesNotContain(
                "김민수 이영희 본식",
                "서울 강남 웨딩",
                "고객 실명",
                "0.987",
                "1234567890",
                "9001011234567",
                "삭제된 댓글 원문",
                "private@example.com",
            )
    }

    @Test
    fun `스냅샷 코덱은 presigned 업로드와 다운로드 URL을 영구 저장하지 않는다`() {
        val encoded = snapshotCodec.encode(
            mapOf(
                "workflowDetails" to mapOf(
                    "uploadUrl" to "https://storage.example/upload?X-Amz-Signature=upload-secret",
                    "replacement_upload_url" to "https://storage.example/replacement?signature=secret",
                    "downloadUrl" to "https://storage.example/download?token=secret",
                    "presignedUrl" to "https://storage.example/presigned?credential=secret",
                    "uploadUrlTtlSeconds" to 1800,
                ),
            ),
        )

        assertThat(encoded)
            .contains("[REDACTED]", "uploadUrlTtlSeconds", "1800")
            .doesNotContain("storage.example", "upload-secret", "signature=secret", "token=secret", "credential=secret")
    }

    @Test
    fun `스냅샷 코덱은 structured AI metadata 전체를 영구 저장하지 않는다`() {
        val encoded = snapshotCodec.encode(
            mapOf(
                "workflowDetails" to mapOf(
                    "structuredAiMetadata" to mapOf(
                        "faceNames" to listOf("김민수", "이영희"),
                        "prompt" to "private@example.com 고객 사진을 분석",
                    ),
                    "structured_ai_metadata" to mapOf("embedding" to listOf(0.1, 0.2)),
                    "status" to "COMPLETED",
                ),
            ),
        )

        assertThat(encoded)
            .contains("status", "COMPLETED", "[REDACTED]")
            .doesNotContain("김민수", "이영희", "private@example.com", "embedding", "0.1")
    }

    @Test
    fun `스냅샷 코덱은 알림 수신자와 payload 전체를 영구 저장하지 않는다`() {
        val encoded = snapshotCodec.encode(
            mapOf(
                "notification" to mapOf(
                    "recipientReference" to "private@example.com",
                    "recipient_reference" to "010-1234-5678",
                    "payload" to mapOf(
                        "customerName" to "김민수",
                        "message" to "서울시 강남구 배송",
                    ),
                    "status" to "PENDING",
                ),
            ),
        )

        assertThat(encoded)
            .contains("status", "PENDING", "[REDACTED]")
            .doesNotContain("private@example.com", "010-1234-5678", "김민수", "서울시 강남구")
    }

    @Test
    fun `스냅샷 코덱은 개인정보와 삭제된 사용자 콘텐츠 원문을 영구 저장하지 않는다`() {
        val encoded = snapshotCodec.encode(
            mapOf(
                "status" to "ACTIVE",
                "version" to 7,
                "name" to "삭제될 이름",
                "displayName" to "삭제될 표시 이름",
                "nickname" to "삭제될 별명",
                "phoneNumber" to "010-1234-5678",
                "address" to "삭제될 주소",
                "content" to "삭제된 댓글 본문",
                "requestText" to "삭제된 보정 요청",
                "deliveryNote" to "삭제된 납품 메모",
                "originalFileName" to "private-wedding.jpg",
            ),
        )

        assertThat(encoded)
            .contains("status", "ACTIVE", "version", "7", "[REDACTED]")
            .doesNotContain(
                "삭제될 이름",
                "삭제될 표시 이름",
                "삭제될 별명",
                "010-1234-5678",
                "삭제될 주소",
                "삭제된 댓글 본문",
                "삭제된 보정 요청",
                "삭제된 납품 메모",
                "private-wedding.jpg",
            )
    }

    @Test
    fun `7일 복원 payload는 PII 복원값은 보존하고 credential 패턴은 제외한다`() {
        val payload = snapshotCodec.encodeRestorePayload(
            AdminAuditTargetType.STUDIO,
            mapOf(
                "type" to "STUDIO",
                "id" to 7,
                "version" to 3,
                "deleted" to false,
                "userId" to 2,
                "name" to "private@example.com password=top-secret Bearer live-access-token",
                "storageKey" to "must-never-enter-payload",
            ),
        )

        assertThat(payload)
            .contains("private@example.com", "[REDACTED]")
            .doesNotContain("top-secret", "live-access-token", "must-never-enter-payload", "storageKey")
    }

    @Test
    fun `영구 감사 사유와 스냅샷은 PII와 secret을 제거하고 대상 라벨을 정규화한다`() {
        val actor = adminAccountFixture.관리자("sanitizer-actor")
        val created = adminAccountService.create(
            actorAdminId = actor.requiredId,
            request = CreateAdminAccountRequest(
                username = "sanitizer-target",
                displayName = "private@example.com 담당",
                reason = "[DATA_CORRECTION] 김민수 서울시 강남구 900101-1234567 계좌 123-456 " +
                    "private@example.com password=top-secret",
            ),
            sourceAddress = "127.0.0.1",
        )

        val audit = auditLogRepository.findAll().single()
        assertThat(audit.targetId).isEqualTo(created.account.id.toString())
        assertThat(audit.actorUsernameSnapshot).isEqualTo("ADMIN #${actor.requiredId}")
        assertThat(audit.targetLabel).isEqualTo("ADMIN_ACCOUNT #${created.account.id}")
        assertThat(audit.reason)
            .isEqualTo("reasonCategory=DATA_CORRECTION operatorReasonProvided=true")
            .doesNotContain("김민수", "서울시", "900101", "123-456", "private@example.com", "top-secret")
        val authEvent = jdbcTemplate.queryForMap(
            """
            SELECT username_snapshot, reason
            FROM admin_auth_events
            WHERE target_admin_id = ?
            ORDER BY id DESC LIMIT 1
            """.trimIndent(),
            created.account.id,
        )
        assertThat(authEvent["username_snapshot"]).isEqualTo("ADMIN #${created.account.id}")
        assertThat(authEvent["reason"])
            .isEqualTo("reasonCategory=DATA_CORRECTION operatorReasonProvided=true")
        val revision = revisionRepository.findAll().single()
        assertThat(revision.afterSnapshot)
            .doesNotContain("private@example.com", "top-secret", "live-access-token")
            .contains("[REDACTED]")
        assertThat(revision.afterRestorePayload)
            .contains("private@example.com")
            .doesNotContain("top-secret", "live-access-token")
    }

    private fun installAuditInsertFailureTrigger() {
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION fail_admin_audit_insert()
            RETURNS TRIGGER
            LANGUAGE plpgsql
            AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'forced audit failure';
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER trg_fail_admin_audit_insert
            BEFORE INSERT ON admin_audit_logs
            FOR EACH ROW
            EXECUTE FUNCTION fail_admin_audit_insert()
            """.trimIndent(),
        )
    }

    private fun removeAuditInsertFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_admin_audit_insert ON admin_audit_logs")
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_admin_audit_insert()")
    }
}
