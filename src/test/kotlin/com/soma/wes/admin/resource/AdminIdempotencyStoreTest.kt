package com.soma.wes.admin.resource

import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.repository.AdminIdempotencyStore
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class AdminIdempotencyStoreTest @Autowired constructor(
    private val store: AdminIdempotencyStore,
    private val adminAccountFixture: AdminAccountFixture,
    private val auditLogRepository: AdminAuditLogRepository,
) {

    @Test
    fun `같은 재처리 키는 한 번만 선점하고 완료 결과를 재사용한다`() {
        val actor = adminAccountFixture.관리자("idempotency-owner")
        val first = reserve(actor.requiredId, "request-hash")
        assertThat(first.existing).isNull()
        store.complete("GALLERY_EMBEDDING", "reprocess-key-001", 17)

        val duplicate = reserve(actor.requiredId, "request-hash")
        assertThat(duplicate.existing?.status).isEqualTo("COMPLETED")
        assertThat(duplicate.existing?.resultPayload).isEqualTo("17")
        assertThat(auditLogRepository.count()).isEqualTo(1)
    }

    @Test
    fun `다른 요청이 같은 재처리 키를 재사용하면 차단한다`() {
        val actor = adminAccountFixture.관리자("idempotency-conflict")
        reserve(actor.requiredId, "first-hash")

        assertThatThrownBy { reserve(actor.requiredId, "different-hash") }
            .isInstanceOfSatisfying(AdminException::class.java) {
                assertThat(it.errorCode).isEqualTo(AdminErrorCode.IDEMPOTENCY_KEY_REUSED)
            }
        assertThat(auditLogRepository.count()).isEqualTo(1)
    }

    private fun reserve(actorAdminId: Long, requestHash: String) =
        store.reserve(
            action = "GALLERY_EMBEDDING",
            idempotencyKey = "reprocess-key-001",
            requestHash = requestHash,
            actorAdminId = actorAdminId,
            targetType = AdminAuditTargetType.GALLERY,
            targetId = "1",
            targetLabel = "테스트 갤러리",
            reason = "임베딩 재처리",
            sourceAddress = "127.0.0.1",
            before = mapOf("id" to 1L, "version" to 0L),
        )
}
