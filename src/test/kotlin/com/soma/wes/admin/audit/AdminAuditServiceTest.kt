package com.soma.wes.admin.audit

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.audit.repository.AdminEntityRevisionRepository
import com.soma.wes.admin.audit.service.AdminAuditQueryService
import com.soma.wes.admin.audit.service.AdminRevisionRestoreService
import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import com.soma.wes.admin.audit.support.AdminRevisionPurgeScheduler
import com.soma.wes.admin.domain.AdminAccountStatus
import com.soma.wes.admin.dto.request.AdminReasonRequest
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
import org.springframework.jdbc.core.JdbcTemplate

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
            request = AdminReasonRequest("정상 상태로 복원"),
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
    fun `만료 리비전은 지우되 영구 감사 로그는 보존한다`() {
        val actor = adminAccountFixture.관리자("purge-actor")
        adminAccountService.create(
            actorAdminId = actor.requiredId,
            request = CreateAdminAccountRequest("purge-target", "정리 대상", "운영 담당자 추가"),
            sourceAddress = "127.0.0.1",
        )
        jdbcTemplate.update("UPDATE admin_entity_revisions SET expires_at = NOW() - INTERVAL '1 second'")

        assertThat(purgeScheduler.purgeExpiredRevisions()).isEqualTo(1)
        assertThat(revisionRepository.count()).isZero()
        assertThat(auditLogRepository.count()).isEqualTo(1)
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
        assertThat(auditLogRepository.findAll().single().reason).isEqualTo("운영 담당자 추가")
    }

    @Test
    fun `스냅샷 코덱은 중첩된 비밀번호와 토큰 값을 마스킹한다`() {
        val encoded = snapshotCodec.encode(
            mapOf(
                "safe" to "visible",
                "passwordHash" to "argon-secret",
                "nested" to mapOf("oauthToken" to "oauth-secret"),
            ),
        )

        assertThat(encoded).contains("visible", "[REDACTED]")
        assertThat(encoded).doesNotContain("argon-secret", "oauth-secret")
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
