package com.soma.wes.admin.impersonation.repository

import com.soma.wes.admin.resource.domain.AdminResourceType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

@Repository
class AdminImpersonationRepository(
    private val jdbcClient: JdbcClient,
) {

    fun insert(session: Session) {
        jdbcClient.sql(
            """
            INSERT INTO admin_impersonation_sessions
                (id, admin_id, admin_session_id, viewer_user_id, viewer_role,
                 target_type, target_id, target_label, reason, source_address,
                 correlation_id, started_at, expires_at)
            VALUES
                (:id, :adminId, :adminSessionId, :viewerUserId, :viewerRole,
                 :targetType, :targetId, :targetLabel, :reason, :sourceAddress,
                 :correlationId, :startedAt, :expiresAt)
            """.trimIndent(),
        )
            .param("id", session.id)
            .param("adminId", session.adminId)
            .param("adminSessionId", session.adminSessionId)
            .param("viewerUserId", session.viewerUserId)
            .param("viewerRole", session.viewerAccessRole)
            .param("targetType", session.targetType.name)
            .param("targetId", session.targetId)
            .param("targetLabel", session.targetLabel)
            .param("reason", session.reason)
            .param("sourceAddress", session.sourceAddress)
            .param("correlationId", session.correlationId)
            .param("startedAt", session.startedAt.toOffsetDateTime())
            .param("expiresAt", session.expiresAt.toOffsetDateTime())
            .update()
    }

    /** ended_at이 비어 있는 만료 세션만 원자적으로 claim한다. 반환 행만 END 감사 대상이다. */
    fun closeExpired(adminSessionId: UUID, now: ZonedDateTime): List<Session> = jdbcClient.sql(
        """
        UPDATE admin_impersonation_sessions
        SET ended_at = :now
        WHERE admin_session_id = :adminSessionId
          AND ended_at IS NULL
          AND expires_at <= :now
        RETURNING id, admin_id, admin_session_id, viewer_user_id, viewer_role,
                  target_type, target_id, target_label, reason, source_address,
                  correlation_id, started_at, expires_at
        """.trimIndent(),
    ).param("adminSessionId", adminSessionId).param("now", now.toOffsetDateTime())
        .query { rs, _ -> session(rs) }.list()

    fun findCurrent(adminSessionId: UUID, now: ZonedDateTime): Session? = findOne(
        "i.admin_session_id = :adminSessionId",
        mapOf("adminSessionId" to adminSessionId, "now" to now.toOffsetDateTime()),
    )

    fun findActive(id: UUID, adminSessionId: UUID, now: ZonedDateTime): Session? = findOne(
        "i.id = :id AND i.admin_session_id = :adminSessionId",
        mapOf("id" to id, "adminSessionId" to adminSessionId, "now" to now.toOffsetDateTime()),
    )

    fun end(id: UUID, adminSessionId: UUID, endedAt: ZonedDateTime): Boolean = jdbcClient.sql(
        """
        UPDATE admin_impersonation_sessions
        SET ended_at = :endedAt
        WHERE id = :id
          AND admin_session_id = :adminSessionId
          AND ended_at IS NULL
          AND expires_at > :endedAt
        """.trimIndent(),
    )
        .param("id", id)
        .param("adminSessionId", adminSessionId)
        .param("endedAt", endedAt.toOffsetDateTime())
        .update() == 1

    private fun findOne(predicate: String, parameters: Map<String, Any>): Session? {
        var statement = jdbcClient.sql(
            """
            SELECT i.id, i.admin_id, i.admin_session_id, i.viewer_user_id, i.viewer_role,
                   i.target_type, i.target_id, i.target_label, i.reason, i.source_address,
                   i.correlation_id, i.started_at, i.expires_at
            FROM admin_impersonation_sessions i
            JOIN admin_sessions s ON s.session_id = i.admin_session_id
            WHERE $predicate
              AND i.ended_at IS NULL
              AND i.expires_at > :now
              AND s.revoked_at IS NULL
              AND s.absolute_expires_at > :now
            """.trimIndent(),
        )
        parameters.forEach { (name, value) -> statement = statement.param(name, value) }
        return statement.query { rs, _ -> session(rs) }.optional().orElse(null)
    }

    private fun session(rs: ResultSet) = Session(
        id = rs.getObject("id", UUID::class.java),
        adminId = rs.getLong("admin_id"),
        adminSessionId = rs.getObject("admin_session_id", UUID::class.java),
        viewerUserId = rs.getLong("viewer_user_id"),
        viewerAccessRole = rs.getString("viewer_role"),
        targetType = AdminResourceType.valueOf(rs.getString("target_type")),
        targetId = rs.getLong("target_id"),
        targetLabel = rs.getString("target_label"),
        reason = rs.getString("reason"),
        sourceAddress = rs.getString("source_address"),
        correlationId = rs.getString("correlation_id"),
        startedAt = rs.getObject("started_at", OffsetDateTime::class.java).toZonedDateTime(),
        expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toZonedDateTime(),
    )

    data class Session(
        val id: UUID,
        val adminId: Long,
        val adminSessionId: UUID,
        val viewerUserId: Long,
        val viewerAccessRole: String,
        val targetType: AdminResourceType,
        val targetId: Long,
        val targetLabel: String,
        val reason: String,
        val sourceAddress: String?,
        /** 대리보기 시작 요청의 MDC trace. 생명주기 식별자는 [id]다. */
        val correlationId: String?,
        val startedAt: ZonedDateTime,
        val expiresAt: ZonedDateTime,
    )
}
