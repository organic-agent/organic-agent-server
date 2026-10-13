package com.soma.wes.auth.repository

import com.soma.wes.auth.dto.AccessStatusDto
import com.soma.wes.auth.dto.requireActive
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class AuthAccessStatusRepository(
    private val jdbcClient: JdbcClient,
) {
    fun requireActive(userId: Long) {
        findStatus(userId).requireActive()
    }

    /** 계정의 탈퇴·정지 여부. 행이 없으면 null — 탈퇴와 같이 다시 로그인으로 보낸다. */
    fun findStatus(userId: Long): AccessStatusDto? = jdbcClient.sql(
        """
        SELECT u.deleted_at IS NOT NULL AS user_deleted,
               u.suspended_at IS NOT NULL AS user_suspended
        FROM users u
        WHERE u.id = :userId
        """.trimIndent(),
    )
        .param("userId", userId)
        .query { rs, _ ->
            AccessStatusDto(
                deleted = rs.getBoolean("user_deleted"),
                suspended = rs.getBoolean("user_suspended"),
            )
        }
        .optional()
        .orElse(null)
}
