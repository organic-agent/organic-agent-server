package com.soma.wes.auth.repository

import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class AuthAccessStatusRepository(
    private val jdbcClient: JdbcClient,
) {
    fun requireActive(userId: Long) {
        val status = jdbcClient.sql(
            """
            SELECT u.deleted_at IS NOT NULL AS user_deleted,
                   u.suspended_at IS NOT NULL AS user_suspended,
                   EXISTS (
                       SELECT 1 FROM studios s
                       WHERE s.user_id = u.id AND s.deleted_at IS NULL AND s.suspended_at IS NOT NULL
                   ) AS studio_suspended
            FROM users u
            WHERE u.id = :userId
            """.trimIndent(),
        )
            .param("userId", userId)
            .query { rs, _ ->
                AccessStatus(
                    userDeleted = rs.getBoolean("user_deleted"),
                    userSuspended = rs.getBoolean("user_suspended"),
                    studioSuspended = rs.getBoolean("studio_suspended"),
                )
            }
            .optional()
            .orElseThrow { TokenException(AuthErrorCode.TOKEN_OWNER_NOT_FOUND) }
        when {
            status.userDeleted -> throw TokenException(AuthErrorCode.TOKEN_OWNER_NOT_FOUND)
            status.userSuspended -> throw TokenException(AuthErrorCode.USER_SUSPENDED)
            status.studioSuspended -> throw TokenException(AuthErrorCode.STUDIO_SUSPENDED)
        }
    }

    private data class AccessStatus(
        val userDeleted: Boolean,
        val userSuspended: Boolean,
        val studioSuspended: Boolean,
    )
}
