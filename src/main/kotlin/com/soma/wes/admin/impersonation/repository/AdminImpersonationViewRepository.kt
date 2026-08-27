package com.soma.wes.admin.impersonation.repository

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.impersonation.dto.AdminImpersonationGalleryViewResponse
import com.soma.wes.admin.impersonation.dto.AdminImpersonationStudioViewResponse
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
                "SELECT user_id FROM studios WHERE id = :id AND deleted_at IS NULL AND suspended_at IS NULL",
            ).param("id", targetId).query { rs, _ -> rs.getLong(1) }.optional().orElseThrow(::invalidTarget)
            val viewerId = requestedViewerUserId ?: ownerId
            requireActiveUser(viewerId)
            if (viewerId == ownerId) {
                Viewer(viewerId, "OWNER")
            } else {
                val role = studioMemberRole(targetId, viewerId) ?: invalidTarget()
                Viewer(viewerId, role)
            }
        }
        AdminResourceType.GALLERY -> {
            val ownerId = jdbcClient.sql(
                """
                SELECT s.user_id
                FROM galleries g
                JOIN studios s ON s.id = g.studio_id
                WHERE g.id = :id AND g.deleted_at IS NULL
                  AND s.deleted_at IS NULL AND s.suspended_at IS NULL
                """.trimIndent(),
            ).param("id", targetId).query { rs, _ -> rs.getLong(1) }.optional().orElseThrow(::invalidTarget)
            val viewerId = requestedViewerUserId ?: ownerId
            requireActiveUser(viewerId)
            val role = if (viewerId == ownerId) {
                "OWNER"
            } else {
                val studioId = jdbcClient.sql("SELECT studio_id FROM galleries WHERE id = :id")
                    .param("id", targetId).query { rs, _ -> rs.getLong(1) }.single()
                studioMemberRole(studioId, viewerId) ?: galleryMemberRole(targetId, viewerId) ?: invalidTarget()
            }
            Viewer(viewerId, role)
        }
        else -> invalidTarget()
    }

    fun view(type: AdminResourceType, targetId: Long, viewer: Viewer): AdminImpersonationViewResponse {
        val resolved = resolveViewer(type, targetId, viewer.userId)
        if (resolved.role != viewer.role) invalidTarget()
        return AdminImpersonationViewResponse(
            profile = profile(viewer.userId),
            studios = studios(viewer.userId, type, targetId),
            galleries = galleries(viewer.userId, type, targetId),
        )
    }

    private fun profile(userId: Long): Map<String, Any?> = jdbcClient.sql(
        """
        SELECT id, nickname, user_type
        FROM users
        WHERE id = :id AND deleted_at IS NULL AND suspended_at IS NULL
        """.trimIndent(),
    ).param("id", userId).query { rs, _ ->
        linkedMapOf(
            "id" to rs.getLong("id"),
            "nickname" to rs.getString("nickname"),
            "userType" to rs.getString("user_type"),
        )
    }.optional().orElseThrow(::invalidTarget)

    private fun studios(
        viewerUserId: Long,
        type: AdminResourceType,
        targetId: Long,
    ): List<AdminImpersonationStudioViewResponse> {
        val scope = if (type == AdminResourceType.STUDIO) "AND s.id = :targetId" else ""
        var statement = jdbcClient.sql(
            """
            SELECT s.id, s.name, s.gallery_url
            FROM studios s
            LEFT JOIN studio_members sm
              ON sm.studio_id = s.id AND sm.user_id = :viewerUserId AND sm.deleted_at IS NULL
            WHERE (s.user_id = :viewerUserId OR sm.id IS NOT NULL)
              AND s.deleted_at IS NULL AND s.suspended_at IS NULL
            $scope
            ORDER BY s.id
            """.trimIndent(),
        ).param("viewerUserId", viewerUserId)
        if (type == AdminResourceType.STUDIO) statement = statement.param("targetId", targetId)
        return statement.query { rs, _ ->
            AdminImpersonationStudioViewResponse(
                id = rs.getLong("id"),
                name = rs.getString("name"),
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
            AdminResourceType.STUDIO -> "AND g.studio_id = :targetId"
            AdminResourceType.GALLERY -> "AND g.id = :targetId"
            else -> ""
        }
        var statement = jdbcClient.sql(
            """
            SELECT g.id, g.studio_id, g.title, g.status, g.selection_deadline,
                   CASE
                       WHEN s.user_id = :viewerUserId THEN 'OWNER'
                       WHEN sm.id IS NOT NULL THEN sm.role
                       ELSE 'MEMBER'
                   END AS access_role,
                   (SELECT COUNT(*) FROM photos p WHERE p.gallery_id = g.id AND p.deleted_at IS NULL) AS photo_count,
                   (SELECT ps.status FROM photo_selections ps
                    WHERE ps.gallery_id = g.id AND ps.deleted_at IS NULL LIMIT 1) AS selection_status,
                   (SELECT COUNT(*) FROM collab_sessions cs
                    WHERE cs.gallery_id = g.id AND cs.deleted_at IS NULL AND cs.revoked_at IS NULL) AS collaboration_count,
                   (SELECT COUNT(*)
                    FROM collab_photo_comments c
                    JOIN collab_photos cp ON cp.id = c.collab_photo_id
                    JOIN collab_sessions cs ON cs.id = cp.collab_session_id
                    WHERE cs.gallery_id = g.id AND c.deleted_at IS NULL) AS comment_count
            FROM galleries g
            JOIN studios s ON s.id = g.studio_id
            LEFT JOIN studio_members sm
              ON sm.studio_id = g.studio_id AND sm.user_id = :viewerUserId AND sm.deleted_at IS NULL
            LEFT JOIN gallery_members gm
              ON gm.gallery_id = g.id AND gm.user_id = :viewerUserId AND gm.deleted_at IS NULL
            WHERE g.deleted_at IS NULL
              AND s.deleted_at IS NULL AND s.suspended_at IS NULL
              AND (
                  s.user_id = :viewerUserId
                  OR sm.id IS NOT NULL
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
        studioId = rs.getLong("studio_id"),
        title = rs.getString("title"),
        status = rs.getString("status"),
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

    private fun studioMemberRole(studioId: Long, userId: Long): String? = jdbcClient.sql(
        """
        SELECT role
        FROM studio_members
        WHERE studio_id = :studioId AND user_id = :userId AND deleted_at IS NULL
        """.trimIndent(),
    ).param("studioId", studioId).param("userId", userId)
        .query { rs, _ -> rs.getString("role") }.optional().orElse(null)

    private fun galleryMemberRole(galleryId: Long, userId: Long): String? = jdbcClient.sql(
        """
        SELECT 'MEMBER'
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

    data class Viewer(val userId: Long, val role: String)
}
