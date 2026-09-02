package com.soma.wes.admin.impersonation.repository

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.impersonation.dto.AdminImpersonationGalleryViewResponse
import com.soma.wes.admin.impersonation.dto.AdminImpersonationWorkspaceViewResponse
import com.soma.wes.admin.impersonation.dto.AdminImpersonationViewResponse
import com.soma.wes.admin.resource.domain.AdminResourceType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/** 관리자 context가 아니라 실제 viewer가 접근 가능한 공개 상태만 구성한다. */
@Repository
class AdminImpersonationViewRepository(
    private val jdbcClient: JdbcClient,
) {

    fun resolveViewer(type: AdminResourceType, targetId: Long, requestedViewerUserId: Long?): Viewer = when (type) {
        AdminResourceType.USER -> {
            requireActiveUser(targetId)
            if (requestedViewerUserId != null && requestedViewerUserId != targetId) invalidTarget()
            Viewer(targetId, "SELF")
        }
        AdminResourceType.STUDIO -> {
            val ownerId = jdbcClient.sql(
                """
                SELECT wm.user_id
                FROM studios s
                JOIN workspace_members wm ON wm.workspace_id = s.workspace_id
                WHERE s.workspace_id = :id AND s.deleted_at IS NULL AND s.suspended_at IS NULL
                  AND wm.role = 'OWNER' AND wm.deleted_at IS NULL
                ORDER BY wm.id LIMIT 1
                """.trimIndent(),
            ).param("id", targetId).query { rs, _ -> rs.getLong(1) }.optional().orElseThrow(::invalidTarget)
            val viewerId = requestedViewerUserId ?: ownerId
            requireActiveUser(viewerId)
            Viewer(viewerId, workspaceAccessRole(targetId, viewerId) ?: invalidTarget())
        }
        AdminResourceType.GALLERY -> {
            val ownerId = jdbcClient.sql(
                """
                SELECT wm.user_id
                FROM galleries g
                JOIN workspaces w ON w.id = g.workspace_id
                JOIN workspace_members wm ON wm.workspace_id = w.id
                LEFT JOIN studios s ON s.workspace_id = w.id
                WHERE g.id = :id AND g.deleted_at IS NULL
                  AND wm.role = 'OWNER' AND wm.deleted_at IS NULL
                  AND (w.type = 'PERSONAL' OR (s.deleted_at IS NULL AND s.suspended_at IS NULL))
                ORDER BY wm.id LIMIT 1
                """.trimIndent(),
            ).param("id", targetId).query { rs, _ -> rs.getLong(1) }.optional().orElseThrow(::invalidTarget)
            val viewerId = requestedViewerUserId ?: ownerId
            requireActiveUser(viewerId)
            val workspaceId = jdbcClient.sql("SELECT workspace_id FROM galleries WHERE id = :id")
                .param("id", targetId).query { rs, _ -> rs.getLong(1) }.single()
            val accessRole = workspaceAccessRole(workspaceId, viewerId)
                ?: galleryMemberAccessRole(targetId, viewerId)
                ?: invalidTarget()
            Viewer(viewerId, accessRole)
        }
        else -> invalidTarget()
    }

    fun view(type: AdminResourceType, targetId: Long, viewer: Viewer): AdminImpersonationViewResponse {
        val resolved = resolveViewer(type, targetId, viewer.userId)
        if (resolved.accessRole != viewer.accessRole) invalidTarget()
        return AdminImpersonationViewResponse(
            profile = profile(viewer.userId),
            workspaces = workspaces(viewer.userId, type, targetId),
            galleries = galleries(viewer.userId, type, targetId),
        )
    }

    private fun profile(userId: Long): Map<String, Any?> = jdbcClient.sql(
        """
        SELECT id, nickname
        FROM users
        WHERE id = :id AND deleted_at IS NULL AND suspended_at IS NULL
        """.trimIndent(),
    ).param("id", userId).query { rs, _ ->
        linkedMapOf(
            "id" to rs.getLong("id"),
            "nickname" to rs.getString("nickname"),
        )
    }.optional().orElseThrow(::invalidTarget)

    private fun workspaces(
        viewerUserId: Long,
        type: AdminResourceType,
        targetId: Long,
    ): List<AdminImpersonationWorkspaceViewResponse> {
        val scope = if (type == AdminResourceType.STUDIO) "AND w.id = :targetId" else ""
        var statement = jdbcClient.sql(
            """
            SELECT w.id AS workspace_id, w.type AS workspace_type, w.name,
                   s.gallery_url,
                   'WORKSPACE_' || wm.role AS access_role
            FROM workspace_members wm
            JOIN workspaces w ON w.id = wm.workspace_id
            LEFT JOIN studios s ON s.workspace_id = w.id
            WHERE wm.user_id = :viewerUserId AND wm.deleted_at IS NULL
              AND w.deleted_at IS NULL
              AND (w.type = 'PERSONAL' OR (s.deleted_at IS NULL AND s.suspended_at IS NULL))
            $scope
            ORDER BY w.type, w.id
            """.trimIndent(),
        ).param("viewerUserId", viewerUserId)
        if (type == AdminResourceType.STUDIO) statement = statement.param("targetId", targetId)
        return statement.query { rs, _ ->
            AdminImpersonationWorkspaceViewResponse(
                workspaceId = rs.getLong("workspace_id"),
                workspaceType = rs.getString("workspace_type"),
                name = rs.getString("name"),
                accessRole = rs.getString("access_role"),
                galleryUrl = rs.getString("gallery_url"),
            )
        }.list()
    }

    private fun galleries(
        viewerUserId: Long,
        type: AdminResourceType,
        targetId: Long,
    ): List<AdminImpersonationGalleryViewResponse> {
        val scope = when (type) {
            AdminResourceType.STUDIO -> "AND g.workspace_id = :targetId"
            AdminResourceType.GALLERY -> "AND g.id = :targetId"
            else -> ""
        }
        var statement = jdbcClient.sql(
            """
            SELECT g.id, g.workspace_id, w.type AS workspace_type, g.created_by_user_id,
                   g.title, g.status, g.workflow_status, g.selection_deadline,
                   CASE
                       WHEN wm.id IS NOT NULL THEN 'WORKSPACE_' || wm.role
                       ELSE 'GALLERY_MEMBER'
                   END AS access_role,
                   (SELECT COUNT(*) FROM photos p WHERE p.gallery_id = g.id AND p.deleted_at IS NULL) AS photo_count,
                   (SELECT ps.status FROM photo_selections ps
                    WHERE ps.gallery_id = g.id AND ps.deleted_at IS NULL LIMIT 1) AS selection_status,
                   (SELECT COUNT(*) FROM collab_sessions cs
                    WHERE cs.gallery_id = g.id AND cs.deleted_at IS NULL AND cs.revoked_at IS NULL) AS collaboration_count,
                   (SELECT COUNT(*)
                    FROM collab_photo_comments c
                    JOIN collab_sessions cs ON cs.id = c.collab_session_id
                    WHERE cs.gallery_id = g.id AND c.deleted_at IS NULL) AS comment_count
            FROM galleries g
            JOIN workspaces w ON w.id = g.workspace_id
            LEFT JOIN studios s ON s.workspace_id = g.workspace_id
            LEFT JOIN workspace_members wm
              ON wm.workspace_id = g.workspace_id AND wm.user_id = :viewerUserId AND wm.deleted_at IS NULL
            LEFT JOIN gallery_members gm
              ON gm.gallery_id = g.id AND gm.user_id = :viewerUserId AND gm.deleted_at IS NULL
            WHERE g.deleted_at IS NULL
              AND (w.type = 'PERSONAL' OR (s.deleted_at IS NULL AND s.suspended_at IS NULL))
              AND (
                  wm.id IS NOT NULL
                  OR (gm.id IS NOT NULL AND g.status <> 'DRAFT')
              )
              $scope
            ORDER BY g.updated_at DESC NULLS LAST, g.id DESC
            """.trimIndent(),
        ).param("viewerUserId", viewerUserId)
        if (type == AdminResourceType.STUDIO || type == AdminResourceType.GALLERY) {
            statement = statement.param("targetId", targetId)
        }
        return statement.query { rs, _ -> gallery(rs) }.list()
    }

    private fun gallery(rs: ResultSet) = AdminImpersonationGalleryViewResponse(
        id = rs.getLong("id"),
        workspaceId = rs.getLong("workspace_id"),
        workspaceType = rs.getString("workspace_type"),
        createdByUserId = rs.getLong("created_by_user_id").takeUnless { rs.wasNull() },
        title = rs.getString("title"),
        publicStatus = rs.getString("status"),
        workflowStatus = rs.getString("workflow_status"),
        selectionDeadline = rs.getObject("selection_deadline", OffsetDateTime::class.java)?.toZonedDateTime(),
        accessRole = rs.getString("access_role"),
        photoCount = rs.getLong("photo_count"),
        selectionStatus = rs.getString("selection_status"),
        collaborationCount = rs.getLong("collaboration_count"),
        commentCount = rs.getLong("comment_count"),
    )

    private fun requireActiveUser(userId: Long) {
        val exists = jdbcClient.sql(
            "SELECT COUNT(*) FROM users WHERE id = :id AND deleted_at IS NULL AND suspended_at IS NULL",
        ).param("id", userId).query { rs, _ -> rs.getLong(1) == 1L }.single()
        if (!exists) invalidTarget()
    }

    private fun workspaceAccessRole(workspaceId: Long, userId: Long): String? = jdbcClient.sql(
        """
        SELECT 'WORKSPACE_' || role AS access_role
        FROM workspace_members
        WHERE workspace_id = :workspaceId AND user_id = :userId AND deleted_at IS NULL
        """.trimIndent(),
    ).param("workspaceId", workspaceId).param("userId", userId)
        .query { rs, _ -> rs.getString("access_role") }.optional().orElse(null)

    private fun galleryMemberAccessRole(galleryId: Long, userId: Long): String? = jdbcClient.sql(
        """
        SELECT 'GALLERY_MEMBER'
        FROM gallery_members gm
        JOIN galleries g ON g.id = gm.gallery_id
        WHERE gm.gallery_id = :galleryId
          AND gm.user_id = :userId
          AND gm.deleted_at IS NULL
          AND g.deleted_at IS NULL
          AND g.status <> 'DRAFT'
        """.trimIndent(),
    ).param("galleryId", galleryId).param("userId", userId)
        .query { rs, _ -> rs.getString(1) }.optional().orElse(null)

    private fun invalidTarget(): Nothing = throw AdminException(AdminErrorCode.INVALID_IMPERSONATION_TARGET)

    data class Viewer(val userId: Long, val accessRole: String)
}
