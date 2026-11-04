package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.repository.projection.IdempotencyRecord
import com.soma.wes.admin.resource.repository.projection.IdempotencyReservation
import com.soma.wes.global.filter.HttpLoggingFilter
import org.slf4j.MDC
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class AdminIdempotencyStore(
    private val jdbcClient: JdbcClient,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reserve(
        action: String,
        idempotencyKey: String,
        requestHash: String,
        targetType: AdminResourceType,
        targetId: String,
    ): IdempotencyReservation {
        val inserted = jdbcClient.sql(
            """
                INSERT INTO admin_idempotency_keys
                    (action, idempotency_key, request_hash, status, target_type, target_id,
                     correlation_id, created_at, updated_at)
                VALUES
                    (:action, :idempotencyKey, :requestHash, 'PENDING', :targetType, :targetId,
                     :correlationId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (action, idempotency_key) DO NOTHING
            """.trimIndent(),
        )
            .param("action", action)
            .param("idempotencyKey", idempotencyKey)
            .param("requestHash", requestHash)
            .param("targetType", targetType.name)
            .param("targetId", targetId)
            .param("correlationId", MDC.get(HttpLoggingFilter.TRACE_ID_KEY) ?: "untracked")
            .update()

        if (inserted == 0) {
            val existing = find(action, idempotencyKey)
                ?: throw AdminException(AdminErrorCode.REPROCESS_ALREADY_REQUESTED)
            if (existing.requestHash != requestHash) {
                throw AdminException(AdminErrorCode.IDEMPOTENCY_KEY_REUSED)
            }
            return IdempotencyReservation(existing = existing)
        }

        return IdempotencyReservation(existing = null)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(action: String, idempotencyKey: String, targets: Long) {
        jdbcClient.sql(
            """
                UPDATE admin_idempotency_keys
                SET status = 'COMPLETED', result_payload = :targets, updated_at = CURRENT_TIMESTAMP
                WHERE action = :action AND idempotency_key = :idempotencyKey AND status = 'PENDING'
            """.trimIndent(),
        )
            .param("targets", targets.toString())
            .param("action", action)
            .param("idempotencyKey", idempotencyKey)
            .update()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(action: String, idempotencyKey: String, failureCode: String) {
        jdbcClient.sql(
            """
                UPDATE admin_idempotency_keys
                SET status = 'FAILED', failure_code = :failureCode, updated_at = CURRENT_TIMESTAMP
                WHERE action = :action AND idempotency_key = :idempotencyKey AND status = 'PENDING'
            """.trimIndent(),
        )
            .param("failureCode", failureCode.take(80))
            .param("action", action)
            .param("idempotencyKey", idempotencyKey)
            .update()
    }

    private fun find(action: String, idempotencyKey: String): IdempotencyRecord? =
        jdbcClient.sql(
            """
                SELECT request_hash, status, result_payload
                FROM admin_idempotency_keys
                WHERE action = :action AND idempotency_key = :idempotencyKey
            """.trimIndent(),
        )
            .param("action", action)
            .param("idempotencyKey", idempotencyKey)
            .query { rs, _ ->
                IdempotencyRecord(
                    requestHash = rs.getString("request_hash"),
                    status = rs.getString("status"),
                    resultPayload = rs.getString("result_payload"),
                )
            }
            .optional()
            .orElse(null)
}
