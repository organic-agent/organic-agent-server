package com.soma.wes.admin.impersonation.repository

import com.soma.wes.admin.resource.domain.AdminResourceType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
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
                    (id, admin_id, target_type, target_id, target_label, reason, source_address,
                     started_at, expires_at)
                VALUES
                    (:id, :adminId, :targetType, :targetId, :targetLabel, :reason, :sourceAddress,
                     :startedAt, :expiresAt)
            """.trimIndent(),
        )
            .param("id", session.id)
            .param("adminId", session.adminId)
            .param("targetType", session.targetType.name)
            .param("targetId", session.targetId)
            .param("targetLabel", session.targetLabel)
            .param("reason", session.reason)
            .param("sourceAddress", session.sourceAddress)
            .param("startedAt", session.startedAt.toOffsetDateTime())
            .param("expiresAt", session.expiresAt.toOffsetDateTime())
            .update()
    }

    fun findActive(id: UUID, adminId: Long, now: ZonedDateTime): Session? = jdbcClient.sql(
        """
            SELECT id, admin_id, target_type, target_id, target_label, reason, source_address,
                   started_at, expires_at
            FROM admin_impersonation_sessions
            WHERE id = :id
              AND admin_id = :adminId
              AND ended_at IS NULL
              AND expires_at > :now
        """.trimIndent(),
    )
        .param("id", id)
        .param("adminId", adminId)
        .param("now", now.toOffsetDateTime())
        .query { rs, _ ->
            Session(
                id = rs.getObject("id", UUID::class.java),
                adminId = rs.getLong("admin_id"),
                targetType = AdminResourceType.valueOf(rs.getString("target_type")),
                targetId = rs.getLong("target_id"),
                targetLabel = rs.getString("target_label"),
                reason = rs.getString("reason"),
                sourceAddress = rs.getString("source_address"),
                startedAt = rs.getObject("started_at", OffsetDateTime::class.java).toZonedDateTime(),
                expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toZonedDateTime(),
            )
        }
        .optional()
        .orElse(null)

    fun end(id: UUID, adminId: Long, endedAt: ZonedDateTime): Boolean = jdbcClient.sql(
        """
            UPDATE admin_impersonation_sessions
            SET ended_at = :endedAt
            WHERE id = :id
              AND admin_id = :adminId
              AND ended_at IS NULL
              AND expires_at > :endedAt
        """.trimIndent(),
    )
        .param("id", id)
        .param("adminId", adminId)
        .param("endedAt", endedAt.toOffsetDateTime())
        .update() == 1

    data class Session(
        val id: UUID,
        val adminId: Long,
        val targetType: AdminResourceType,
        val targetId: Long,
        val targetLabel: String,
        val reason: String,
        val sourceAddress: String?,
        val startedAt: ZonedDateTime,
        val expiresAt: ZonedDateTime,
    )
}
