package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.resource.domain.AdminInboxEventType
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminInboxWorkStatus
import com.soma.wes.admin.resource.dto.AdminNotificationInboxItemResponse
import com.soma.wes.admin.resource.dto.AdminNotificationInboxPageResponse
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

@Repository
class AdminNotificationInboxRepository(
    private val jdbcClient: JdbcClient,
) {

    fun create(
        eventType: AdminInboxEventType,
        targetType: AdminResourceType,
        targetId: Long,
        safeSummary: String,
        correlationId: String?,
        idempotencyKeyHash: String,
        createdByAdminId: Long,
    ): Long {
        val insertedId = jdbcClient.sql(
            """
            INSERT INTO admin_notification_inbox
                (event_type, target_type, target_id, work_status, safe_summary, correlation_id,
                 idempotency_key_hash, created_by_admin_id, version, created_at, updated_at)
            VALUES
                (:eventType, :targetType, :targetId, 'OPEN', :safeSummary, :correlationId,
                 :idempotencyKeyHash, :createdByAdminId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (idempotency_key_hash) DO NOTHING
            RETURNING id
            """.trimIndent(),
        )
            .param("eventType", eventType.name)
            .param("targetType", targetType.name)
            .param("targetId", targetId)
            .param("safeSummary", safeSummary)
            .param("correlationId", correlationId)
            .param("idempotencyKeyHash", idempotencyKeyHash)
            .param("createdByAdminId", createdByAdminId)
            .query { rs, _ -> rs.getLong(1) }
            .optional()
            .orElse(null)
        // 상위 admin_idempotency_keys가 보존되는 동안의 정상 반복은 service replay가 처리한다.
        // 그 기록만 정리된 뒤 같은 키가 다시 오면 DB unique 예외를 500으로 노출하지 않고,
        // 이미 영속 인박스 이벤트에 사용된 키라는 명시적 충돌로 닫는다.
        return insertedId ?: throw AdminException(AdminErrorCode.IDEMPOTENCY_KEY_REUSED)
    }

    fun findPage(adminId: Long, page: Int, size: Int): AdminNotificationInboxPageResponse {
        val totalCount = jdbcClient.sql("SELECT COUNT(*) FROM admin_notification_inbox")
            .query { rs, _ -> rs.getLong(1) }
            .single()
        val unreadCount = jdbcClient.sql(
            """
            SELECT COUNT(*)
            FROM admin_notification_inbox n
            WHERE NOT EXISTS (
                SELECT 1 FROM admin_notification_inbox_reads r
                WHERE r.notification_id = n.id AND r.admin_id = :adminId
            )
            """.trimIndent(),
        )
            .param("adminId", adminId)
            .query { rs, _ -> rs.getLong(1) }
            .single()
        val contents = jdbcClient.sql(
            """
            SELECT n.id, n.version, n.event_type, n.target_type, n.target_id, n.work_status,
                   n.safe_summary, n.correlation_id, n.created_at,
                   r.read_at
            FROM admin_notification_inbox n
            LEFT JOIN admin_notification_inbox_reads r
              ON r.notification_id = n.id AND r.admin_id = :adminId
            ORDER BY (r.notification_id IS NULL) DESC, n.created_at DESC, n.id DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
        )
            .param("adminId", adminId)
            .param("limit", size)
            .param("offset", page.toLong() * size)
            .query { rs, _ -> mapItem(rs) }
            .list()
        return AdminNotificationInboxPageResponse(
            page = page,
            size = size,
            totalCount = totalCount,
            hasNext = (page.toLong() + 1) * size < totalCount,
            unreadCount = unreadCount,
            contents = contents,
        )
    }

    fun findOne(adminId: Long, notificationId: Long): AdminNotificationInboxItemResponse? =
        jdbcClient.sql(
            """
            SELECT n.id, n.version, n.event_type, n.target_type, n.target_id, n.work_status,
                   n.safe_summary, n.correlation_id, n.created_at,
                   r.read_at
            FROM admin_notification_inbox n
            LEFT JOIN admin_notification_inbox_reads r
              ON r.notification_id = n.id AND r.admin_id = :adminId
            WHERE n.id = :notificationId
            """.trimIndent(),
        )
            .param("adminId", adminId)
            .param("notificationId", notificationId)
            .query { rs, _ -> mapItem(rs) }
            .optional()
            .orElse(null)

    /** 이벤트 version이 맞을 때만 관리자별 단조 read receipt를 추가한다. 반복 호출은 성공이다. */
    fun markRead(adminId: Long, notificationId: Long, expectedVersion: Long): Boolean =
        jdbcClient.sql(
            """
            INSERT INTO admin_notification_inbox_reads
                (notification_id, admin_id, notification_version, read_at)
            SELECT n.id, :adminId, n.version, CURRENT_TIMESTAMP
            FROM admin_notification_inbox n
            WHERE n.id = :notificationId AND n.version = :expectedVersion
            ON CONFLICT (notification_id, admin_id) DO NOTHING
            """.trimIndent(),
        )
            .param("adminId", adminId)
            .param("notificationId", notificationId)
            .param("expectedVersion", expectedVersion)
            .update() == 1

    private fun mapItem(rs: ResultSet): AdminNotificationInboxItemResponse {
        val readAt = rs.getObject("read_at", OffsetDateTime::class.java)?.toZonedDateTime()
        return AdminNotificationInboxItemResponse(
            id = rs.getLong("id"),
            version = rs.getLong("version"),
            eventType = AdminInboxEventType.valueOf(rs.getString("event_type")),
            targetType = AdminResourceType.valueOf(rs.getString("target_type")),
            targetId = rs.getLong("target_id"),
            workStatus = AdminInboxWorkStatus.valueOf(rs.getString("work_status")),
            summary = rs.getString("safe_summary"),
            correlationId = rs.getString("correlation_id"),
            read = readAt != null,
            readAt = readAt,
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toZonedDateTime(),
        )
    }
}
