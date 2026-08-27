package com.soma.wes.admin.audit

import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

/**
 * V40은 이미 배포된 역사 migration이라 V44 이후 스키마에 다시 실행할 수 없다.
 * 별도 임시 database를 V39까지만 만든 뒤 V40을 실제 Flyway 경로로 적용해 checksum과
 * dirty-data 정리 계약을 계속 검증한다. database는 테스트 컨테이너와 함께 폐기된다.
 */
object V40AuditHardeningMigrationVerifier {

    fun verify(
        postgres: PostgreSQLContainer<*>,
        snapshotCodec: AdminAuditSnapshotCodec,
    ) {
        val databaseName = "wes_v40_${UUID.randomUUID().toString().replace("-", "")}"
        createDatabase(postgres, databaseName)
        val jdbcUrl = postgres.jdbcUrl.substringBeforeLast('/') + "/$databaseName"

        migrate(postgres, jdbcUrl, "39")
        val jdbc = JdbcTemplate(DriverManagerDataSource(jdbcUrl, postgres.username, postgres.password))
        val actorId = seedDirtyV39Data(jdbc)
        migrate(postgres, jdbcUrl, "40")

        verifyRevision(jdbc, snapshotCodec)
        verifyAudit(jdbc, actorId)
        verifyAuth(jdbc, actorId)
        verifyTrash(jdbc, actorId)
        verifyDatabaseGuards(jdbc)
    }

    private fun createDatabase(postgres: PostgreSQLContainer<*>, databaseName: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE \"$databaseName\"")
            }
        }
    }

    private fun migrate(postgres: PostgreSQLContainer<*>, jdbcUrl: String, target: String) {
        Flyway.configure()
            .dataSource(jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion(target))
            .load()
            .migrate()
    }

    private fun seedDirtyV39Data(jdbc: JdbcTemplate): Long {
        val actorId = requireNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO admin_accounts
                    (username, display_name, password_hash, status, must_change_password,
                     failed_login_attempts, password_changed_at, created_at, updated_at)
                VALUES
                    ('v40-migration-actor', '과거 운영자', 'test-password-hash', 'ACTIVE', FALSE,
                     0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
            ),
        )
        val beforeSnapshot =
            """{"type":"GALLERY","id":987654,"version":2,"title":"이전 본식 제목","status":"DRAFT","deleted":false,"createdAt":"2026-08-27T13:10:00Z","updatedAt":"2026-99-99T99:99:99+99:99","private@example.com":"ACTIVE","fields":{"status":"DRAFT","customerName-private@example.com":"OPEN"}}"""
        val afterSnapshot =
            """{"type":"GALLERY","id":987654,"version":3,"label":"홍길동 본식","title":"private@example.com 본식 password=top-secret","status":"OPEN","deleted":false,"content":"삭제된 고객 본문","accountNumber":1234567890}"""

        jdbc.update(
            """
            INSERT INTO admin_audit_logs
                (action, outcome, actor_admin_id, actor_username_snapshot, target_type, target_id,
                 target_label, source_address, reason, changed_fields, revision_number, created_at)
            VALUES
                ('RESOURCE_UPDATED', 'SUCCESS', ?, '홍길동 운영자', 'GALLERY', '987654',
                 'private@example.com 본식 제목', '203.0.113.41',
                 '[DATA_CORRECTION] 고객 이름과 제목 정정',
                 'status,title, updatedAt,private@example.com,accountId,label,status', 100,
                 CURRENT_TIMESTAMP),
                ('RESOURCE_UPDATED', 'SUCCESS', ?, 'Alice', 'GALLERY', '987656',
                 'Hong wedding', '203.0.113.44',
                 'note=Alice customerName=Hong memo=01012345678', NULL, NULL, CURRENT_TIMESTAMP)
            """.trimIndent(),
            actorId,
            actorId,
        )
        jdbc.update(
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
            actorId,
            actorId,
        )
        jdbc.update(
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
            actorId,
            actorId,
        )
        jdbc.update(
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
            actorId,
            actorId,
        )
        jdbc.update(
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
        return actorId
    }

    private fun verifyRevision(jdbc: JdbcTemplate, snapshotCodec: AdminAuditSnapshotCodec) {
        val revision = jdbc.queryForMap(
            """
            SELECT target_id, target_version, snapshot_schema_version,
                   before_snapshot, after_snapshot, before_restore_payload, after_restore_payload,
                   restore_expires_at = created_at + INTERVAL '7 days' AS restore_window_clamped,
                   expires_at = restore_expires_at AS legacy_expiry_synchronized
            FROM admin_entity_revisions
            WHERE target_type = 'GALLERY' AND revision_number = 100
            """.trimIndent(),
        )
        assertThat(revision["target_id"]).isEqualTo("987654")
        assertThat(revision["target_version"]).isEqualTo(3L)
        assertThat(revision["snapshot_schema_version"]).isEqualTo(3)
        assertThat(revision["restore_window_clamped"]).isEqualTo(true)
        assertThat(revision["legacy_expiry_synchronized"]).isEqualTo(true)

        val permanentText = revision["before_snapshot"].toString() + revision["after_snapshot"].toString()
        val permanentBefore = requireNotNull(snapshotCodec.decode(revision["before_snapshot"] as String))
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
        val restoreText = revision["before_restore_payload"].toString() +
            revision["after_restore_payload"].toString()
        assertThat(restoreText)
            .contains("이전 본식 제목", "private@example.com 본식", "password=[REDACTED]")
            .doesNotContain("top-secret", "삭제된 고객 본문", "accountNumber", "label")

        val expiredPayloads = jdbc.queryForMap(
            """
            SELECT before_restore_payload, after_restore_payload
            FROM admin_entity_revisions
            WHERE target_type = 'GALLERY' AND revision_number = 101
            """.trimIndent(),
        )
        assertThat(expiredPayloads["before_restore_payload"]).isNull()
        assertThat(expiredPayloads["after_restore_payload"]).isNull()
    }

    private fun verifyAudit(jdbc: JdbcTemplate, actorId: Long) {
        val audit = jdbc.queryForMap(
            """
            SELECT actor_username_snapshot, target_label, source_address, reason, changed_fields
            FROM admin_audit_logs
            WHERE target_type = 'GALLERY' AND target_id = '987654'
            """.trimIndent(),
        )
        assertThat(audit["actor_username_snapshot"]).isEqualTo("ADMIN #$actorId")
        assertThat(audit["target_label"]).isEqualTo("GALLERY #987654")
        assertThat(audit["source_address"]).isNull()
        assertThat(audit["reason"])
            .isEqualTo("reasonCategory=DATA_CORRECTION operatorReasonProvided=true")
        assertThat(audit["changed_fields"]).isEqualTo("status,title")

        val disguisedPiiAudit = jdbc.queryForMap(
            """
            SELECT actor_username_snapshot, target_label, source_address, reason
            FROM admin_audit_logs
            WHERE target_type = 'GALLERY' AND target_id = '987656'
            """.trimIndent(),
        )
        assertThat(disguisedPiiAudit["actor_username_snapshot"]).isEqualTo("ADMIN #$actorId")
        assertThat(disguisedPiiAudit["target_label"]).isEqualTo("GALLERY #987656")
        assertThat(disguisedPiiAudit["source_address"]).isNull()
        assertThat(disguisedPiiAudit["reason"])
            .isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=true")
    }

    private fun verifyAuth(jdbc: JdbcTemplate, actorId: Long) {
        val authRows = jdbc.queryForList(
            """
            SELECT username_snapshot, source_address, reason
            FROM admin_auth_events
            ORDER BY id
            """.trimIndent(),
        )
        assertThat(authRows[0]["username_snapshot"]).isEqualTo("ADMIN #$actorId")
        assertThat(authRows[0]["source_address"]).isNull()
        assertThat(authRows[0]["reason"])
            .isEqualTo("reasonCategory=SECURITY_RESPONSE operatorReasonProvided=true")
        assertThat(authRows[1]["username_snapshot"]).isNull()
        assertThat(authRows[1]["source_address"]).isNull()
        assertThat(authRows[1]["reason"])
            .isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=false")
    }

    private fun verifyTrash(jdbc: JdbcTemplate, actorId: Long) {
        val batches = jdbc.queryForList(
            """
            SELECT root_id, root_label, actor_username, reason
            FROM admin_trash_batches
            WHERE root_id IN (88001, 88002)
            ORDER BY root_id
            """.trimIndent(),
        )
        assertThat(batches[0]["root_label"]).isEqualTo("GALLERY #88001")
        assertThat(batches[0]["actor_username"]).isEqualTo("ADMIN #$actorId")
        assertThat(batches[0]["reason"])
            .isEqualTo("reasonCategory=CUSTOMER_REQUEST operatorReasonProvided=true")
        assertThat(batches[1]["root_label"]).isEqualTo("PHOTO #88002")
        assertThat(batches[1]["actor_username"]).isNull()
        assertThat(batches[1]["reason"])
            .isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=false")

        val children = jdbc.queryForList(
            """
            SELECT resource_id, actor_username, reason
            FROM admin_child_trash_records
            WHERE resource_id IN (88101, 88102)
            ORDER BY resource_id
            """.trimIndent(),
        )
        assertThat(children[0]["actor_username"]).isEqualTo("ADMIN #$actorId")
        assertThat(children[0]["reason"])
            .isEqualTo("reasonCategory=DATA_CORRECTION operatorReasonProvided=true")
        assertThat(children[1]["actor_username"]).isNull()
        assertThat(children[1]["reason"])
            .isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=false")
    }

    private fun verifyDatabaseGuards(jdbc: JdbcTemplate) {
        assertThatThrownBy {
            jdbc.update("UPDATE admin_audit_logs SET reason = 'tampered'")
        }.hasMessageContaining("immutable")
        assertThatThrownBy {
            jdbc.update("UPDATE admin_entity_revisions SET after_snapshot = '{}' WHERE revision_number = 100")
        }.hasMessageContaining("immutable")
    }
}
