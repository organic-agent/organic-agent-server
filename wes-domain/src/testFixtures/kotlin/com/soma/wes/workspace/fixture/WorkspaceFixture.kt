package com.soma.wes.workspace.fixture

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

/**
 * 작업공간의 지워진 상태를 만든다. 작업공간·멤버십 삭제는 관리자 앱이 SQL로 하는 일이라
 * 공개 앱 엔티티에는 쓰는 메서드가 없다 — 행을 직접 고친다.
 */
@Component
class WorkspaceFixture(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun 삭제됨(workspaceId: Long, at: ZonedDateTime) {
        jdbcTemplate.update(
            "UPDATE workspaces SET deleted_at = ?, version = version + 1, updated_at = now() WHERE id = ?",
            at.toOffsetDateTime(), workspaceId,
        )
    }

    /** 멤버십만 끝난 상태. 다른 멤버와 작업공간은 그대로다. */
    fun 멤버_탈퇴(workspaceId: Long, userId: Long, at: ZonedDateTime) {
        jdbcTemplate.update(
            "UPDATE workspace_members SET deleted_at = ?, version = version + 1, updated_at = now() " +
                "WHERE workspace_id = ? AND user_id = ?",
            at.toOffsetDateTime(), workspaceId, userId,
        )
    }
}
