package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.resource.domain.AdminResourceType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZonedDateTime

@Repository
class AdminCascadeTrashRepository(
    private val jdbcClient: JdbcClient,
) {

    fun createBatch(
        actorAdminId: Long,
        rootType: AdminResourceType,
        rootId: Long,
        rootLabel: String,
        reason: String,
        deletedAt: ZonedDateTime,
        restoreUntil: ZonedDateTime,
    ): Long = jdbcClient.sql(
        """
        INSERT INTO admin_trash_batches (
            root_type, root_id, root_label, actor_admin_id, actor_username, reason,
            status, deleted_at, restore_until, created_at, updated_at
        )
        VALUES (
            :rootType, :rootId, :rootLabel, :actorAdminId,
            (SELECT username FROM admin_accounts WHERE id = :actorAdminId), :reason,
            'ACTIVE', :deletedAt, :restoreUntil, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        )
        RETURNING id
        """.trimIndent(),
    )
        .param("rootType", rootType.name)
        .param("rootId", rootId)
        .param("rootLabel", rootLabel)
        .param("actorAdminId", actorAdminId)
        .param("reason", reason)
        .param("deletedAt", deletedAt.toOffsetDateTime())
        .param("restoreUntil", restoreUntil.toOffsetDateTime())
        .query { rs, _ -> rs.getLong("id") }
        .single()

    fun moveToTrash(
        batchId: Long,
        rootType: AdminResourceType,
        rootId: Long,
        deletedAt: ZonedDateTime,
    ): Map<String, Long> {
        SOFT_TARGETS.forEach { target ->
            val predicate = scopePredicate(rootType, target.type) ?: return@forEach
            jdbcClient.sql(
                """
                WITH affected AS (
                    UPDATE ${target.table} r
                    SET deleted_at = :deletedAt,
                        version = r.version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE r.deleted_at IS NULL
                      AND ($predicate)
                    RETURNING r.id
                )
                INSERT INTO admin_trash_entries (batch_id, resource_type, resource_id, created_at)
                SELECT :batchId, :resourceType, id, CURRENT_TIMESTAMP
                FROM affected
                """.trimIndent(),
            )
                .param("deletedAt", deletedAt.toOffsetDateTime())
                .param("rootId", rootId)
                .param("batchId", batchId)
                .param("resourceType", target.type)
                .update()
        }
        return affectedCounts(batchId)
    }

    fun hasOverlappingActiveBatch(rootType: AdminResourceType, rootId: Long): Boolean {
        val predicate = overlapPredicate(rootType)
        return jdbcClient.sql(
            """
            SELECT COUNT(*)
            FROM admin_trash_batches b
            WHERE b.status IN ('ACTIVE', 'PURGING')
              AND ($predicate)
            """.trimIndent(),
        )
            .param("rootId", rootId)
            .query { rs, _ -> rs.getLong(1) > 0 }
            .single()
    }

    fun find(batchId: Long): BatchRow? = jdbcClient.sql(
        """
        SELECT id, root_type, root_id, root_label, actor_admin_id, actor_username, reason,
               status, deleted_at, restore_until, restored_at, purged_at,
               purge_attempt_count, purge_started_at, failure_code
        FROM admin_trash_batches
        WHERE id = :batchId
        """.trimIndent(),
    )
        .param("batchId", batchId)
        .query { rs, _ -> batch(rs) }
        .optional()
        .orElse(null)

    fun findActiveByRoot(rootType: AdminResourceType, rootId: Long): BatchRow? = jdbcClient.sql(
        """
        SELECT id, root_type, root_id, root_label, actor_admin_id, actor_username, reason,
               status, deleted_at, restore_until, restored_at, purged_at,
               purge_attempt_count, purge_started_at, failure_code
        FROM admin_trash_batches
        WHERE root_type = :rootType AND root_id = :rootId AND status IN ('ACTIVE', 'PURGING')
        """.trimIndent(),
    )
        .param("rootType", rootType.name)
        .param("rootId", rootId)
        .query { rs, _ -> batch(rs) }
        .optional()
        .orElse(null)

    fun list(limit: Int = 200): List<BatchRow> = jdbcClient.sql(
        """
        SELECT id, root_type, root_id, root_label, actor_admin_id, actor_username, reason,
               status, deleted_at, restore_until, restored_at, purged_at,
               purge_attempt_count, purge_started_at, failure_code
        FROM admin_trash_batches
        ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'PURGING' THEN 1 ELSE 2 END,
                 deleted_at DESC, id DESC
        LIMIT :limit
        """.trimIndent(),
    )
        .param("limit", limit)
        .query { rs, _ -> batch(rs) }
        .list()

    fun affectedCounts(batchId: Long): Map<String, Long> = jdbcClient.sql(
        """
        SELECT resource_type, COUNT(*) AS affected_count
        FROM admin_trash_entries
        WHERE batch_id = :batchId
        GROUP BY resource_type
        ORDER BY resource_type
        """.trimIndent(),
    )
        .param("batchId", batchId)
        .query { rs, _ -> rs.getString("resource_type") to rs.getLong("affected_count") }
        .list()
        .toMap()

    fun restore(batch: BatchRow): Int = SOFT_TARGETS.sumOf { target ->
        jdbcClient.sql(
            """
            UPDATE ${target.table} r
            SET deleted_at = NULL,
                version = r.version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE r.deleted_at = :deletedAt
              AND EXISTS (
                  SELECT 1
                  FROM admin_trash_entries e
                  WHERE e.batch_id = :batchId
                    AND e.resource_type = :resourceType
                    AND e.resource_id = r.id
              )
            """.trimIndent(),
        )
            .param("deletedAt", batch.deletedAt.toOffsetDateTime())
            .param("batchId", batch.id)
            .param("resourceType", target.type)
            .update()
    }

    fun markRestored(batchId: Long): Int = jdbcClient.sql(
        """
        UPDATE admin_trash_batches
        SET status = 'RESTORED', restored_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
            failure_code = NULL
        WHERE id = :batchId AND status = 'ACTIVE'
        """.trimIndent(),
    ).param("batchId", batchId).update()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimExpired(now: ZonedDateTime, staleBefore: ZonedDateTime, limit: Int = 20): List<BatchRow> =
        jdbcClient.sql(
            """
            WITH candidates AS (
                SELECT id
                FROM admin_trash_batches
                WHERE (status = 'ACTIVE' AND restore_until <= :now)
                   OR (status = 'PURGING' AND purge_started_at < :staleBefore)
                ORDER BY restore_until, id
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
            ), claimed AS (
                UPDATE admin_trash_batches b
                SET status = 'PURGING', purge_attempt_count = b.purge_attempt_count + 1,
                    purge_started_at = :now, failure_code = NULL, updated_at = CURRENT_TIMESTAMP
                FROM candidates c
                WHERE b.id = c.id
                RETURNING b.*
            )
            SELECT id, root_type, root_id, root_label, actor_admin_id, actor_username, reason,
                   status, deleted_at, restore_until, restored_at, purged_at,
                   purge_attempt_count, purge_started_at, failure_code
            FROM claimed
            ORDER BY restore_until, id
            """.trimIndent(),
        )
            .param("now", now.toOffsetDateTime())
            .param("staleBefore", staleBefore.toOffsetDateTime())
            .param("limit", limit)
            .query { rs, _ -> batch(rs) }
            .list()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markPurgeFailed(batchId: Long, failureCode: String) {
        jdbcClient.sql(
            """
            UPDATE admin_trash_batches
            SET status = 'ACTIVE', purge_started_at = NULL, failure_code = :failureCode,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :batchId AND status = 'PURGING'
            """.trimIndent(),
        )
            .param("batchId", batchId)
            .param("failureCode", failureCode.take(120))
            .update()
    }

    fun findPurgeObjects(batch: BatchRow): PurgeObjects {
        val galleryPredicate = galleryScopePredicate(batch.rootType)
        val photoPredicate = when (batch.rootType) {
            AdminResourceType.PHOTO -> "p.id = :rootId"
            else -> galleryPredicate?.let { "p.gallery_id IN (SELECT g.id FROM galleries g WHERE $it)" }
        }
        val photos = if (photoPredicate == null) emptyList() else jdbcClient.sql(
            """
            SELECT p.storage_key, p.preview_key
            FROM photos p
            WHERE $photoPredicate
            """.trimIndent(),
        ).param("rootId", batch.rootId).query { rs, _ ->
            PhotoObject(rs.getString("storage_key"), rs.getString("preview_key"))
        }.list()

        val retouchPredicate = when (batch.rootType) {
            AdminResourceType.PHOTO -> "rp.photo_id = :rootId"
            AdminResourceType.RETOUCH_REQUEST -> "rp.round_id = :rootId"
            else -> galleryPredicate?.let { "rp.gallery_id IN (SELECT g.id FROM galleries g WHERE $it)" }
        }
        val retouchKeys = if (retouchPredicate == null) emptyList() else jdbcClient.sql(
            """
            SELECT rp.annotation_key, rp.result_key
            FROM retouch_photos rp
            WHERE $retouchPredicate
            """.trimIndent(),
        ).param("rootId", batch.rootId).query { rs, _ ->
            listOfNotNull(rs.getString("annotation_key"), rs.getString("result_key"))
        }.list().flatten()
        return PurgeObjects(photos, retouchKeys)
    }

    fun deleteRoot(batch: BatchRow): Int = when (batch.rootType) {
        AdminResourceType.USER -> {
            jdbcClient.sql("DELETE FROM refresh_tokens WHERE user_id = :rootId")
                .param("rootId", batch.rootId).update()
            jdbcClient.sql("DELETE FROM gallery_members WHERE user_id = :rootId")
                .param("rootId", batch.rootId).update()
            jdbcClient.sql("DELETE FROM studios WHERE user_id = :rootId")
                .param("rootId", batch.rootId).update()
            jdbcClient.sql("DELETE FROM users WHERE id = :rootId")
                .param("rootId", batch.rootId).update()
        }
        AdminResourceType.STUDIO -> deleteById("studios", batch.rootId)
        AdminResourceType.GALLERY -> deleteById("galleries", batch.rootId)
        AdminResourceType.PHOTO -> deleteById("photos", batch.rootId)
        AdminResourceType.SELECTION -> deleteById("photo_selections", batch.rootId)
        AdminResourceType.COLLABORATION -> deleteById("collab_sessions", batch.rootId)
        AdminResourceType.ALBUM -> deleteById("photo_folder_groups", batch.rootId)
        AdminResourceType.RETOUCH_REQUEST -> deleteById("retouch_rounds", batch.rootId)
    }

    fun markPurged(batchId: Long): Int = jdbcClient.sql(
        """
        UPDATE admin_trash_batches
        SET status = 'PURGED', purged_at = CURRENT_TIMESTAMP, purge_started_at = NULL,
            failure_code = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE id = :batchId AND status = 'PURGING'
        """.trimIndent(),
    ).param("batchId", batchId).update()

    fun revokeRefreshToken(userId: Long) {
        jdbcClient.sql("DELETE FROM refresh_tokens WHERE user_id = :userId")
            .param("userId", userId)
            .update()
    }

    private fun deleteById(table: String, id: Long): Int = jdbcClient.sql("DELETE FROM $table WHERE id = :id")
        .param("id", id)
        .update()

    private fun batch(rs: ResultSet): BatchRow = BatchRow(
        id = rs.getLong("id"),
        rootType = AdminResourceType.valueOf(rs.getString("root_type")),
        rootId = rs.getLong("root_id"),
        rootLabel = rs.getString("root_label"),
        actorAdminId = rs.getLong("actor_admin_id").takeUnless { rs.wasNull() },
        actorUsername = rs.getString("actor_username"),
        reason = rs.getString("reason"),
        status = rs.getString("status"),
        deletedAt = dateTime(rs, "deleted_at")!!,
        restoreUntil = dateTime(rs, "restore_until")!!,
        restoredAt = dateTime(rs, "restored_at"),
        purgedAt = dateTime(rs, "purged_at"),
        purgeAttemptCount = rs.getInt("purge_attempt_count"),
        purgeStartedAt = dateTime(rs, "purge_started_at"),
        failureCode = rs.getString("failure_code"),
    )

    private fun dateTime(rs: ResultSet, column: String): ZonedDateTime? =
        rs.getObject(column, OffsetDateTime::class.java)?.toZonedDateTime()

    private fun scopePredicate(root: AdminResourceType, target: String): String? = when (root) {
        AdminResourceType.USER -> when (target) {
            "USER" -> "r.id = :rootId"
            "GALLERY_MEMBER" -> """
                r.user_id = :rootId
                AND r.gallery_id IN (SELECT g.id FROM galleries g WHERE g.deleted_at IS NULL)
            """.trimIndent()
            "STUDIO" -> "r.user_id = :rootId"
            "GALLERY" -> "r.studio_id IN (SELECT s.id FROM studios s WHERE s.user_id = :rootId)"
            else -> childGalleryPredicate(target, "g.studio_id IN (SELECT s.id FROM studios s WHERE s.user_id = :rootId)")
        }
        AdminResourceType.STUDIO -> when (target) {
            "STUDIO" -> "r.id = :rootId"
            "GALLERY" -> "r.studio_id = :rootId"
            else -> childGalleryPredicate(target, "g.studio_id = :rootId")
        }
        AdminResourceType.GALLERY -> when (target) {
            "GALLERY" -> "r.id = :rootId"
            else -> childGalleryPredicate(target, "g.id = :rootId")
        }
        AdminResourceType.PHOTO -> if (target == "PHOTO") "r.id = :rootId" else null
        AdminResourceType.SELECTION -> if (target == "SELECTION") "r.id = :rootId" else null
        AdminResourceType.COLLABORATION -> when (target) {
            "COLLABORATION" -> "r.id = :rootId"
            "COLLAB_COMMENT" -> "r.collab_photo_id IN (SELECT cp.id FROM collab_photos cp WHERE cp.collab_session_id = :rootId)"
            else -> null
        }
        AdminResourceType.ALBUM -> if (target == "ALBUM") "r.id = :rootId" else null
        AdminResourceType.RETOUCH_REQUEST -> if (target == "RETOUCH_REQUEST") "r.id = :rootId" else null
    }

    private fun childGalleryPredicate(target: String, galleryPredicate: String): String? = when (target) {
        "PHOTO", "SELECTION", "COLLABORATION", "ALBUM", "RETOUCH_REQUEST" ->
            "r.gallery_id IN (SELECT g.id FROM galleries g WHERE $galleryPredicate)"
        "COLLAB_COMMENT" ->
            "r.collab_photo_id IN (SELECT cp.id FROM collab_photos cp JOIN collab_sessions cs ON cs.id = cp.collab_session_id WHERE cs.gallery_id IN (SELECT g.id FROM galleries g WHERE $galleryPredicate))"
        else -> null
    }

    private fun overlapPredicate(root: AdminResourceType): String = when (root) {
        AdminResourceType.USER -> """
            (b.root_type = 'USER' AND b.root_id = :rootId)
            OR (b.root_type = 'STUDIO' AND b.root_id IN (SELECT s.id FROM studios s WHERE s.user_id = :rootId))
            OR (b.root_type = 'STUDIO' AND b.root_id IN (SELECT g.studio_id FROM gallery_members gm JOIN galleries g ON g.id = gm.gallery_id WHERE gm.user_id = :rootId))
            OR (b.root_type = 'GALLERY' AND b.root_id IN (SELECT g.id FROM galleries g JOIN studios s ON s.id = g.studio_id WHERE s.user_id = :rootId))
            OR (b.root_type = 'GALLERY' AND b.root_id IN (SELECT gm.gallery_id FROM gallery_members gm WHERE gm.user_id = :rootId))
            OR (b.root_type = 'USER' AND b.root_id IN (SELECT s.user_id FROM gallery_members gm JOIN galleries g ON g.id = gm.gallery_id JOIN studios s ON s.id = g.studio_id WHERE gm.user_id = :rootId))
            OR (b.root_type = 'PHOTO' AND b.root_id IN (SELECT p.id FROM photos p JOIN galleries g ON g.id = p.gallery_id JOIN studios s ON s.id = g.studio_id WHERE s.user_id = :rootId))
            OR (b.root_type = 'SELECTION' AND b.root_id IN (SELECT x.id FROM photo_selections x JOIN galleries g ON g.id = x.gallery_id JOIN studios s ON s.id = g.studio_id WHERE s.user_id = :rootId))
            OR (b.root_type = 'COLLABORATION' AND b.root_id IN (SELECT x.id FROM collab_sessions x JOIN galleries g ON g.id = x.gallery_id JOIN studios s ON s.id = g.studio_id WHERE s.user_id = :rootId))
            OR (b.root_type = 'ALBUM' AND b.root_id IN (SELECT x.id FROM photo_folder_groups x JOIN galleries g ON g.id = x.gallery_id JOIN studios s ON s.id = g.studio_id WHERE s.user_id = :rootId))
            OR (b.root_type = 'RETOUCH_REQUEST' AND b.root_id IN (SELECT x.id FROM retouch_rounds x JOIN galleries g ON g.id = x.gallery_id JOIN studios s ON s.id = g.studio_id WHERE s.user_id = :rootId))
        """.trimIndent()
        AdminResourceType.STUDIO -> descendantOverlap("g.studio_id = :rootId", includeStudio = true)
        AdminResourceType.GALLERY -> descendantOverlap("g.id = :rootId", includeStudio = false)
        else -> "b.root_type = '${root.name}' AND b.root_id = :rootId"
    }

    private fun descendantOverlap(galleryPredicate: String, includeStudio: Boolean): String = buildList {
        if (includeStudio) add("(b.root_type = 'STUDIO' AND b.root_id = :rootId)")
        add("(b.root_type = 'USER' AND b.root_id IN (SELECT gm.user_id FROM gallery_members gm WHERE gm.gallery_id IN (SELECT g.id FROM galleries g WHERE $galleryPredicate)))")
        add("(b.root_type = 'GALLERY' AND b.root_id IN (SELECT g.id FROM galleries g WHERE $galleryPredicate))")
        add("(b.root_type = 'PHOTO' AND b.root_id IN (SELECT p.id FROM photos p WHERE p.gallery_id IN (SELECT g.id FROM galleries g WHERE $galleryPredicate)))")
        add("(b.root_type = 'SELECTION' AND b.root_id IN (SELECT x.id FROM photo_selections x WHERE x.gallery_id IN (SELECT g.id FROM galleries g WHERE $galleryPredicate)))")
        add("(b.root_type = 'COLLABORATION' AND b.root_id IN (SELECT x.id FROM collab_sessions x WHERE x.gallery_id IN (SELECT g.id FROM galleries g WHERE $galleryPredicate)))")
        add("(b.root_type = 'ALBUM' AND b.root_id IN (SELECT x.id FROM photo_folder_groups x WHERE x.gallery_id IN (SELECT g.id FROM galleries g WHERE $galleryPredicate)))")
        add("(b.root_type = 'RETOUCH_REQUEST' AND b.root_id IN (SELECT x.id FROM retouch_rounds x WHERE x.gallery_id IN (SELECT g.id FROM galleries g WHERE $galleryPredicate)))")
    }.joinToString(" OR ")

    private fun galleryScopePredicate(root: AdminResourceType): String? = when (root) {
        AdminResourceType.USER -> "g.studio_id IN (SELECT s.id FROM studios s WHERE s.user_id = :rootId)"
        AdminResourceType.STUDIO -> "g.studio_id = :rootId"
        AdminResourceType.GALLERY -> "g.id = :rootId"
        else -> null
    }

    data class BatchRow(
        val id: Long,
        val rootType: AdminResourceType,
        val rootId: Long,
        val rootLabel: String,
        val actorAdminId: Long?,
        val actorUsername: String?,
        val reason: String,
        val status: String,
        val deletedAt: ZonedDateTime,
        val restoreUntil: ZonedDateTime,
        val restoredAt: ZonedDateTime?,
        val purgedAt: ZonedDateTime?,
        val purgeAttemptCount: Int,
        val purgeStartedAt: ZonedDateTime?,
        val failureCode: String?,
    )

    data class PhotoObject(val storageKey: String, val previewKey: String?)
    data class PurgeObjects(val photos: List<PhotoObject>, val retouchKeys: List<String>)

    private data class SoftTarget(val type: String, val table: String)

    companion object {
        private val SOFT_TARGETS = listOf(
            SoftTarget("USER", "users"),
            SoftTarget("GALLERY_MEMBER", "gallery_members"),
            SoftTarget("STUDIO", "studios"),
            SoftTarget("GALLERY", "galleries"),
            SoftTarget("PHOTO", "photos"),
            SoftTarget("SELECTION", "photo_selections"),
            SoftTarget("COLLABORATION", "collab_sessions"),
            SoftTarget("COLLAB_COMMENT", "collab_photo_comments"),
            SoftTarget("ALBUM", "photo_folder_groups"),
            SoftTarget("RETOUCH_REQUEST", "retouch_rounds"),
        )
    }
}
