package com.soma.wes.activity.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** 엔티티가 없는 활동 시각 테이블(`gallery_activity`·`workspace_activity`)을 `ON CONFLICT` UPSERT로 올리므로 JDBC. */
@Repository
class ActivityRepository(private val jdbcClient: JdbcClient) {
    fun recordGallery(galleryId: Long, at: ZonedDateTime) {
        jdbcClient.sql("""
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
        """).params(mapOf("id" to galleryId, "at" to at.toOffsetDateTime())).update()
    }

    fun recordWorkspace(workspaceId: Long, at: ZonedDateTime) {
        jdbcClient.sql("""
            INSERT INTO workspace_activity(workspace_id, last_activity_at)
            SELECT id, :at FROM workspaces WHERE id = :id AND deleted_at IS NULL
            ON CONFLICT (workspace_id) DO UPDATE
              SET last_activity_at = greatest(workspace_activity.last_activity_at, EXCLUDED.last_activity_at)
        """).params(mapOf("id" to workspaceId, "at" to at.toOffsetDateTime())).update()
    }

    fun findGalleryActivity(galleryIds: Collection<Long>): Map<Long, ZonedDateTime> {
        if (galleryIds.isEmpty()) return emptyMap()
        return jdbcClient.sql("""
            SELECT a.gallery_id, a.last_activity_at FROM gallery_activity a
            JOIN galleries g ON g.id = a.gallery_id
            WHERE a.gallery_id IN (:ids) AND g.deleted_at IS NULL
        """).param("ids", galleryIds).query { rs, _ ->
            rs.getLong("gallery_id") to rs.getObject("last_activity_at", OffsetDateTime::class.java).toZonedDateTime()
        }.list().toMap()
    }

    fun findWorkspaceActivity(workspaceIds: Collection<Long>): Map<Long, ZonedDateTime> {
        if (workspaceIds.isEmpty()) return emptyMap()
        return jdbcClient.sql("""
            SELECT a.workspace_id, a.last_activity_at FROM workspace_activity a
            JOIN workspaces w ON w.id = a.workspace_id
            WHERE a.workspace_id IN (:ids) AND w.deleted_at IS NULL
        """).param("ids", workspaceIds).query { rs, _ ->
            rs.getLong("workspace_id") to rs.getObject("last_activity_at", OffsetDateTime::class.java).toZonedDateTime()
        }.list().toMap()
    }
}
