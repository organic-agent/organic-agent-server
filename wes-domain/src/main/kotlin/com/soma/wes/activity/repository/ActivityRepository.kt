package com.soma.wes.activity.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZonedDateTime

@Repository
class ActivityRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun recordGallery(galleryId: Long, at: ZonedDateTime) {
        jdbc.update("""
            WITH changed AS (
                INSERT INTO gallery_activity(gallery_id, last_activity_at)
                SELECT g.id, :at FROM galleries g JOIN workspaces w ON w.id = g.workspace_id
                WHERE g.id = :id AND g.deleted_at IS NULL AND w.deleted_at IS NULL
                ON CONFLICT (gallery_id) DO UPDATE
                  SET last_activity_at = greatest(gallery_activity.last_activity_at, EXCLUDED.last_activity_at)
                RETURNING gallery_id
            )
            INSERT INTO workspace_activity(workspace_id, last_activity_at)
            SELECT g.workspace_id, :at FROM changed c JOIN galleries g ON g.id = c.gallery_id
            ON CONFLICT (workspace_id) DO UPDATE
              SET last_activity_at = greatest(workspace_activity.last_activity_at, EXCLUDED.last_activity_at)
        """, mapOf("id" to galleryId, "at" to at.toOffsetDateTime()))
    }

    fun recordWorkspace(workspaceId: Long, at: ZonedDateTime) {
        jdbc.update("""
            INSERT INTO workspace_activity(workspace_id, last_activity_at)
            SELECT id, :at FROM workspaces WHERE id = :id AND deleted_at IS NULL
            ON CONFLICT (workspace_id) DO UPDATE
              SET last_activity_at = greatest(workspace_activity.last_activity_at, EXCLUDED.last_activity_at)
        """, mapOf("id" to workspaceId, "at" to at.toOffsetDateTime()))
    }

    fun findGalleryActivity(galleryIds: Collection<Long>): Map<Long, ZonedDateTime> {
        if (galleryIds.isEmpty()) return emptyMap()
        return jdbc.query("""
            SELECT a.gallery_id, a.last_activity_at FROM gallery_activity a
            JOIN galleries g ON g.id = a.gallery_id
            WHERE a.gallery_id IN (:ids) AND g.deleted_at IS NULL
        """, mapOf("ids" to galleryIds)) { rs, _ ->
            rs.getLong("gallery_id") to rs.getObject("last_activity_at", OffsetDateTime::class.java).toZonedDateTime()
        }.toMap()
    }

    fun findWorkspaceActivity(workspaceIds: Collection<Long>): Map<Long, ZonedDateTime> {
        if (workspaceIds.isEmpty()) return emptyMap()
        return jdbc.query("""
            SELECT a.workspace_id, a.last_activity_at FROM workspace_activity a
            JOIN workspaces w ON w.id = a.workspace_id
            WHERE a.workspace_id IN (:ids) AND w.deleted_at IS NULL
        """, mapOf("ids" to workspaceIds)) { rs, _ ->
            rs.getLong("workspace_id") to rs.getObject("last_activity_at", OffsetDateTime::class.java).toZonedDateTime()
        }.toMap()
    }
}
