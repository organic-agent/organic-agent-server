package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.resource.domain.AdminResourceType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/** DB가 비동기 작업의 단일 진실 원천이 되도록 claim과 상태 전이를 원자적으로 수행한다. */
@Repository
class AdminWorkflowExecutionRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    fun claimProcessingJobs(
        limit: Int,
        maxAttempts: Int,
        retryDelay: Duration,
        staleTimeout: Duration,
    ): List<ProcessingExecutionJob> = jdbcClient.sql(
        """
        WITH picked AS (
            SELECT id
            FROM admin_processing_jobs
            WHERE (
                attempt_count < :maxAttempts
                AND (
                    status = 'PENDING'
                    OR (status = 'FAILED' AND COALESCE(last_run_at, created_at) <=
                        CURRENT_TIMESTAMP - make_interval(secs => :retrySeconds))
                )
              )
              OR (
                status = 'DISPATCHING' AND failure_code = :claimedNotSent
                AND last_run_at <= CURRENT_TIMESTAMP - make_interval(secs => :staleSeconds)
              )
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
        )
        UPDATE admin_processing_jobs j
        SET status = 'DISPATCHING', failure_code = :claimedNotSent,
            last_run_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
        FROM picked p
        WHERE j.id = p.id
        RETURNING j.id, j.job_type, j.target_type, j.target_id, j.revision_id,
                  j.payload::TEXT, j.attempt_count, j.actor_admin_id, j.reason
        """.trimIndent(),
    )
        .param("maxAttempts", maxAttempts.coerceAtLeast(1))
        .param("retrySeconds", retryDelay.seconds.coerceAtLeast(0))
        .param("staleSeconds", staleTimeout.seconds.coerceAtLeast(1))
        .param("claimedNotSent", CLAIMED_NOT_SENT)
        .param("limit", limit.coerceIn(1, 100))
        .query { rs, _ -> ProcessingExecutionJob(
            id = rs.getLong("id"),
            jobType = rs.getString("job_type"),
            targetType = AdminResourceType.valueOf(rs.getString("target_type")),
            targetId = rs.getLong("target_id"),
            revisionId = rs.getLong("revision_id").takeUnless { rs.wasNull() },
            payload = jsonMap(rs.getString("payload")),
            attemptCount = rs.getInt("attempt_count"),
            actorAdminId = rs.getObject("actor_admin_id", java.lang.Long::class.java)?.toLong(),
            reason = rs.getString("reason"),
        ) }
        .list()

    /**
     * Lambda 비동기 이벤트 수명과 함수 제한을 모두 넘긴 exact-photo 작업을 실패로 회수한다.
     *
     * `DISPATCHING/failure_code IS NULL`은 attempt를 소비한 직후 프로세스가 중단됐거나,
     * 외부 호출은 끝났지만 DISPATCHED finalize 전에 중단된 상태다. 둘을 즉시 구분할 수는
     * 없으므로 이벤트가 더는 도착하거나 실행될 수 없는 [dispatchedTimeout] 뒤에만 FAILED로
     * 바꾼다. 다음 retry는 attempt를 증가시키며, 늦은 이전 이벤트는 worker의 attempt CAS에서
     * 탈락한다. 명시적인 결과 불명 상태도 같은 안전 시간이 지난 뒤 회수한다.
     *
     * 상태 변경 뒤 감사가 실패하면 호출 측 트랜잭션이 이 UPDATE도 함께 rollback한다.
     */
    @Transactional
    fun markStaleExactPhotoJobsFailed(
        limit: Int,
        dispatchedTimeout: Duration,
    ): List<ProcessingExecutionJob> = jdbcClient.sql(
        """
        WITH picked AS (
            SELECT id
            FROM admin_processing_jobs
            WHERE job_type IN ('DERIVATIVE', 'EMBEDDING')
              AND (
                status = 'DISPATCHED'
                OR (
                    status = 'DISPATCHING'
                    AND (failure_code IS NULL OR failure_code = :outcomeUnknown)
                )
              )
              AND COALESCE(last_run_at, updated_at, created_at) <=
                  CURRENT_TIMESTAMP - make_interval(secs => :timeoutSeconds)
            ORDER BY COALESCE(last_run_at, updated_at, created_at), id
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
        )
        UPDATE admin_processing_jobs j
        SET status = 'FAILED', failure_code = :failureCode,
            last_run_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
        FROM picked p
        WHERE j.id = p.id
          AND (
            j.status = 'DISPATCHED'
            OR (
                j.status = 'DISPATCHING'
                AND (j.failure_code IS NULL OR j.failure_code = :outcomeUnknown)
            )
          )
        RETURNING j.id, j.job_type, j.target_type, j.target_id, j.revision_id,
                  j.payload::TEXT, j.attempt_count, j.actor_admin_id, j.reason
        """.trimIndent(),
    )
        .param("timeoutSeconds", dispatchedTimeout.seconds.coerceAtLeast(1))
        .param("failureCode", DISPATCH_RESULT_TIMEOUT)
        .param("outcomeUnknown", DISPATCH_OUTCOME_UNKNOWN)
        .param("limit", limit.coerceIn(1, 100))
        .query { rs, _ -> ProcessingExecutionJob(
            id = rs.getLong("id"),
            jobType = rs.getString("job_type"),
            targetType = AdminResourceType.valueOf(rs.getString("target_type")),
            targetId = rs.getLong("target_id"),
            revisionId = rs.getLong("revision_id").takeUnless { rs.wasNull() },
            payload = jsonMap(rs.getString("payload")),
            attemptCount = rs.getInt("attempt_count"),
            actorAdminId = rs.getObject("actor_admin_id", java.lang.Long::class.java)?.toLong(),
            reason = rs.getString("reason"),
        ) }
        .list()

    fun targetActive(type: AdminResourceType, id: Long): Boolean {
        val table = targetTable(type)
        return jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM $table WHERE id = :id AND deleted_at IS NULL)")
            .param("id", id)
            .query { rs, _ -> rs.getBoolean(1) }
            .single()
    }

    /** 실제 실행 직전 대상 생존과 lease를 CAS하고, 이 시점에만 attempt를 소비한다. */
    fun markProcessingExecutionStarted(
        jobId: Long,
        attemptCount: Int,
        targetType: AdminResourceType,
        targetId: Long,
    ): Int? {
        val table = targetTable(targetType)
        return jdbcClient.sql(
            """
            UPDATE admin_processing_jobs
            SET attempt_count = attempt_count + 1, failure_code = NULL,
                last_run_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :jobId AND status = 'DISPATCHING'
              AND attempt_count = :attemptCount AND failure_code = :claimedNotSent
              AND EXISTS(
                  SELECT 1 FROM $table
                  WHERE id = :targetId AND deleted_at IS NULL
              )
            RETURNING attempt_count
            """.trimIndent(),
        )
            .param("jobId", jobId)
            .param("attemptCount", attemptCount)
            .param("targetId", targetId)
            .param("claimedNotSent", CLAIMED_NOT_SENT)
            .query { rs, _ -> rs.getInt("attempt_count") }
            .optional()
            .orElse(null)
    }

    /** 명확한 설정/검증 실패는 FAILED 전이와 실제 실행 attempt 소비를 한 CAS로 묶는다. */
    fun markProcessingJobFailedBeforeStart(
        id: Long,
        attemptCount: Int,
        failureCode: String,
    ): Int = jdbcClient.sql(
        """
        UPDATE admin_processing_jobs
        SET status = 'FAILED', attempt_count = attempt_count + 1,
            failure_code = :failureCode, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
          AND failure_code = :claimedNotSent
        """.trimIndent(),
    )
        .param("failureCode", failureCode.take(80))
        .param("id", id)
        .param("attemptCount", attemptCount)
        .param("claimedNotSent", CLAIMED_NOT_SENT)
        .update()

    /** 마지막 실제 attempt 뒤 복구된 로컬 lease는 재실행하지 않고 실패로 종결한다. */
    fun markProcessingJobAttemptsExhausted(
        id: Long,
        attemptCount: Int,
    ): Int = jdbcClient.sql(
        """
        UPDATE admin_processing_jobs
        SET status = 'FAILED', failure_code = :failureCode, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
          AND failure_code = :claimedNotSent
        """.trimIndent(),
    )
        .param("failureCode", MAX_ATTEMPTS_EXHAUSTED)
        .param("id", id)
        .param("attemptCount", attemptCount)
        .param("claimedNotSent", CLAIMED_NOT_SENT)
        .update()

    /** lease가 그대로이고 대상이 실제 삭제된 경우에만 미실행 claim을 취소한다. */
    fun cancelClaimedProcessingJobIfTargetDeleted(
        id: Long,
        attemptCount: Int,
        targetType: AdminResourceType,
        targetId: Long,
    ): Int {
        val table = targetTable(targetType)
        return jdbcClient.sql(
            """
            UPDATE admin_processing_jobs
            SET status = 'CANCELED', failure_code = 'TARGET_DELETED', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
              AND failure_code = :claimedNotSent
              AND NOT EXISTS(
                  SELECT 1 FROM $table
                  WHERE id = :targetId AND deleted_at IS NULL
              )
            """.trimIndent(),
        )
            .param("id", id)
            .param("attemptCount", attemptCount)
            .param("targetId", targetId)
            .param("claimedNotSent", CLAIMED_NOT_SENT)
            .update()
    }

    /** 외부 호출 정상 반환 뒤 send-start claim이 그대로일 때만 DISPATCHED로 확정한다. */
    fun markProcessingJobDispatched(id: Long, attemptCount: Int): Int = jdbcClient.sql(
        """
        UPDATE admin_processing_jobs
        SET status = 'DISPATCHED', failure_code = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
          AND failure_code IS NULL
        """.trimIndent(),
    )
        .param("id", id)
        .param("attemptCount", attemptCount)
        .update()

    /** 외부 전송 결과를 확정할 수 없으면 DISPATCHING을 유지하되 자동 claim 대상에서 제외한다. */
    fun markProcessingJobAmbiguous(
        id: Long,
        attemptCount: Int,
        failureCode: String,
    ): Int = jdbcClient.sql(
        """
        UPDATE admin_processing_jobs
        SET failure_code = :failureCode, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
          AND failure_code IS NULL
        """.trimIndent(),
    )
        .param("failureCode", failureCode.take(80))
        .param("id", id)
        .param("attemptCount", attemptCount)
        .update()

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(value: String): Map<String, Any?> =
        objectMapper.readValue(value, Map::class.java).entries
            .associate { it.key.toString() to it.value }

    data class ProcessingExecutionJob(
        val id: Long,
        val jobType: String,
        val targetType: AdminResourceType,
        val targetId: Long,
        val revisionId: Long?,
        val payload: Map<String, Any?>,
        val attemptCount: Int,
        val actorAdminId: Long?,
        val reason: String,
    )

    companion object {
        const val CLAIMED_NOT_SENT = "CLAIMED_NOT_SENT"
        const val DISPATCH_OUTCOME_UNKNOWN = "DISPATCH_OUTCOME_UNKNOWN"
        const val MAX_ATTEMPTS_EXHAUSTED = "MAX_ATTEMPTS_EXHAUSTED"
        const val DISPATCH_RESULT_TIMEOUT = "DISPATCH_RESULT_TIMEOUT"
    }

    private fun targetTable(type: AdminResourceType): String = when (type) {
        AdminResourceType.USER -> "users"
        AdminResourceType.STUDIO -> "studios"
        AdminResourceType.GALLERY -> "galleries"
        AdminResourceType.PHOTO -> "photos"
        AdminResourceType.SELECTION -> "photo_selections"
        AdminResourceType.COLLABORATION -> "collab_sessions"
        AdminResourceType.RETOUCH_REQUEST -> "retouch_rounds"
        else -> throw IllegalArgumentException("processing workflow target is unsupported: $type")
    }
}

class WorkflowExecutionException(val failureCode: String) : RuntimeException(failureCode)
