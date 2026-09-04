package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.audit.support.AdminAuditSanitizer
import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminResourceType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZonedDateTime

@Repository
class AdminChildTrashRepository(
    private val jdbcClient: JdbcClient,
    private val auditSanitizer: AdminAuditSanitizer,
) {

    /** product claim과 같은 advisory mutex를 parent row보다 먼저 잡아 child mutation 경쟁을 직렬화한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockProductPurgeCoordination(type: AdminChildTrashType, resourceId: Long, parentId: Long) {
        val key = when (type) {
            AdminChildTrashType.COLLAB_COMMENT -> jdbcClient.sql(
                """
                SELECT 'PHOTO' AS resource_type, child.photo_id AS resource_id
                FROM collab_photo_comments child
                WHERE child.id = :resourceId AND child.collab_session_id = :parentId
                """.trimIndent(),
            )
            AdminChildTrashType.COLLAB_LIKE -> jdbcClient.sql(
                """
                SELECT 'PHOTO' AS resource_type, child.photo_id AS resource_id
                FROM collab_photo_likes child
                WHERE child.id = :resourceId AND child.collab_session_id = :parentId
                """.trimIndent(),
            )
            AdminChildTrashType.ALBUM_TEMPLATE -> jdbcClient.sql(
                """
                SELECT 'GALLERY' AS resource_type, parent.gallery_id AS resource_id
                FROM photo_folder_groups parent
                WHERE parent.id = :parentId AND :resourceId IS NOT NULL
                """.trimIndent(),
            )
            AdminChildTrashType.RETOUCH_ITEM -> jdbcClient.sql(
                """
                SELECT 'PHOTO' AS resource_type, child.photo_id AS resource_id
                FROM retouch_photos child
                WHERE child.id = :resourceId AND child.round_id = :parentId
                """.trimIndent(),
            )
        }
            .param("resourceId", resourceId)
            .param("parentId", parentId)
            .query { rs, _ -> rs.getString("resource_type") to rs.getLong("resource_id") }
            .optional()
            .orElse(null)
            ?: return
        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:coordinationKey, 0))")
            .param("coordinationKey", "WES_PRODUCT_PURGE:${key.first}:${key.second}")
            .query { _, _ -> Unit }
            .single()
    }

    fun lockParent(type: AdminChildTrashType, parentId: Long): ParentState? {
        val mapping = mapping(type)
        return jdbcClient.sql(
            """
            SELECT version, deleted_at IS NOT NULL AS deleted
            FROM ${mapping.parentTable}
            WHERE id = :parentId
            FOR UPDATE
            """.trimIndent(),
        )
            .param("parentId", parentId)
            .query { rs, _ -> ParentState(rs.getLong("version"), rs.getBoolean("deleted")) }
            .optional()
            .orElse(null)
    }

    fun isCascadeBlocked(type: AdminChildTrashType, resourceId: Long, parentId: Long): Boolean =
        jdbcClient.sql(
            """
            SELECT EXISTS (
                SELECT 1
                FROM admin_trash_entries e
                JOIN admin_trash_batches b ON b.id = e.batch_id
                WHERE b.status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
                  AND (
                      (e.resource_type = :resourceType AND e.resource_id = :resourceId)
                      OR (e.resource_type = :parentType AND e.resource_id = :parentId)
                  )
            )
            """.trimIndent(),
        )
            .param("resourceType", type.name)
            .param("resourceId", resourceId)
            .param("parentType", type.parentType.name)
            .param("parentId", parentId)
            .query { rs, _ -> rs.getBoolean(1) }
            .single()

    /**
     * 제품 purge claim과 관리자 child mutation의 범위가 겹치는지 검사한다. 호출자는 먼저
     * [lockParent]를 잡는다. 제품 claim도 같은 parent를 먼저 잠그므로 검사 직후 삽입 경쟁이 없다.
     */
    fun isProductPurgeClaimBlocked(type: AdminChildTrashType, resourceId: Long, parentId: Long): Boolean =
        jdbcClient.sql(
            """
            SELECT EXISTS (
                SELECT 1
                FROM product_purge_claims claim
                WHERE (
                    claim.resource_type = 'GALLERY'
                    AND (
                        (:resourceType IN ('COLLAB_COMMENT', 'COLLAB_LIKE') AND EXISTS (
                            SELECT 1 FROM collab_sessions parent
                            WHERE parent.id = :parentId AND parent.gallery_id = claim.resource_id
                        ))
                        OR (:resourceType = 'ALBUM_TEMPLATE' AND EXISTS (
                            SELECT 1 FROM photo_folder_groups parent
                            WHERE parent.id = :parentId AND parent.gallery_id = claim.resource_id
                        ))
                        OR (:resourceType = 'RETOUCH_ITEM' AND EXISTS (
                            SELECT 1 FROM retouch_rounds parent
                            WHERE parent.id = :parentId AND parent.gallery_id = claim.resource_id
                        ))
                    )
                )
                OR (
                    claim.resource_type = 'PHOTO'
                    AND (
                        (:resourceType = 'COLLAB_COMMENT' AND EXISTS (
                            SELECT 1
                            FROM collab_photo_comments child
                            WHERE child.id = :resourceId AND child.photo_id = claim.resource_id
                        ))
                        OR (:resourceType = 'COLLAB_LIKE' AND EXISTS (
                            SELECT 1
                            FROM collab_photo_likes child
                            WHERE child.id = :resourceId AND child.photo_id = claim.resource_id
                        ))
                        OR (:resourceType = 'RETOUCH_ITEM' AND EXISTS (
                            SELECT 1 FROM retouch_photos child
                            WHERE child.id = :resourceId AND child.photo_id = claim.resource_id
                        ))
                    )
                )
            )
            """.trimIndent(),
        )
            .param("resourceType", type.name)
            .param("resourceId", resourceId)
            .param("parentId", parentId)
            .query { rs, _ -> rs.getBoolean(1) }
            .single()

    fun findActiveForUpdate(type: AdminChildTrashType, resourceId: Long): ChildTrashRow? =
        jdbcClient.sql(
            """
            SELECT id, resource_type, resource_id, parent_type, parent_id, actor_admin_id,
                   actor_username, reason, status, deleted_at, restore_until, restored_at,
                   purged_at, purge_attempt_count, purge_started_at, failure_code
            FROM admin_child_trash_records
            WHERE resource_type = :resourceType AND resource_id = :resourceId
              AND status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
            FOR UPDATE
            """.trimIndent(),
        )
            .param("resourceType", type.name)
            .param("resourceId", resourceId)
            .query { rs, _ -> row(rs) }
            .optional()
            .orElse(null)

    fun list(historyLimit: Int = 500): List<ChildTrashRow> = jdbcClient.sql(
        """
        WITH visible AS (
            SELECT r.*, 0 AS bucket
            FROM admin_child_trash_records r
            WHERE r.status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
            UNION ALL
            SELECT history.*, 1 AS bucket
            FROM (
                SELECT r.*
                FROM admin_child_trash_records r
                WHERE r.status NOT IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
                ORDER BY r.deleted_at DESC, r.id DESC
                LIMIT :historyLimit
            ) history
        )
        SELECT id, resource_type, resource_id, parent_type, parent_id, actor_admin_id,
               actor_username, reason, status, deleted_at, restore_until, restored_at,
               purged_at, purge_attempt_count, purge_started_at, failure_code
        FROM visible
        ORDER BY bucket, deleted_at DESC, id DESC
        """.trimIndent(),
    )
        .param("historyLimit", historyLimit)
        .query { rs, _ -> row(rs) }
        .list()

    fun findForUpdate(id: Long): ChildTrashRow? =
        jdbcClient.sql(
            """
            SELECT id, resource_type, resource_id, parent_type, parent_id, actor_admin_id,
                   actor_username, reason, status, deleted_at, restore_until, restored_at,
                   purged_at, purge_attempt_count, purge_started_at, failure_code
            FROM admin_child_trash_records
            WHERE id = :id
            FOR UPDATE
            """.trimIndent(),
        )
            .param("id", id)
            .query { rs, _ -> row(rs) }
            .optional()
            .orElse(null)

    fun softDeleteChild(
        type: AdminChildTrashType,
        resourceId: Long,
        parentId: Long,
        expectedChildVersion: Long?,
        deletedAt: ZonedDateTime,
    ): Int {
        val mapping = mapping(type)
        // Layout replacement takes a shared lock on the selected template. Take the
        // matching exclusive row lock before checking the zero-reference predicate so
        // a concurrent replacement cannot attach a template after this check and just
        // before the soft delete.
        if (type == AdminChildTrashType.ALBUM_TEMPLATE) {
            val locked = jdbcClient.sql(
                """
                SELECT id
                FROM admin_album_templates
                WHERE id = :resourceId AND deleted_at IS NULL
                FOR UPDATE
                """.trimIndent(),
            )
                .param("resourceId", resourceId)
                .query { rs, _ -> rs.getLong("id") }
                .optional()
                .isPresent
            if (!locked) return 0
        }
        val versionPredicate = expectedChildVersion?.let { "AND r.version = :expectedChildVersion" }.orEmpty()
        var statement = jdbcClient.sql(
            """
            UPDATE ${mapping.childTable} r
            SET deleted_at = :deletedAt, version = r.version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE r.id = :resourceId AND r.deleted_at IS NULL
              AND (${mapping.childParentPredicate})
              $versionPredicate
            """.trimIndent(),
        )
            .param("resourceId", resourceId)
            .param("deletedAt", deletedAt.toOffsetDateTime())
        statement = statement.param("parentId", parentId)
        if (expectedChildVersion != null) statement = statement.param("expectedChildVersion", expectedChildVersion)
        return statement.update()
    }

    fun restoreChild(row: ChildTrashRow, expectedChildVersion: Long?): Int {
        val mapping = mapping(row.type)
        val versionPredicate = expectedChildVersion?.let { "AND r.version = :expectedChildVersion" }.orEmpty()
        var statement = jdbcClient.sql(
            """
            UPDATE ${mapping.childTable} r
            SET deleted_at = NULL, version = r.version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE r.id = :resourceId AND r.deleted_at = :deletedAt
              AND (${mapping.childParentPredicate})
              $versionPredicate
            """.trimIndent(),
        )
            .param("resourceId", row.resourceId)
            .param("deletedAt", row.deletedAt.toOffsetDateTime())
        statement = statement.param("parentId", row.parentId)
        if (expectedChildVersion != null) statement = statement.param("expectedChildVersion", expectedChildVersion)
        return statement.update()
    }

    /**
     * 좋아요를 휴지통에 둔 사이 같은 하객이 다시 누르면 새 active 행이 생긴다. 이 경우 예전
     * 행을 복원하면 active 유니크 계약을 깨므로, 새 반응을 보존하고 관리자 복원을 충돌로 막는다.
     */
    fun hasRestoreIdentityConflict(row: ChildTrashRow): Boolean {
        if (row.type != AdminChildTrashType.COLLAB_LIKE) return false
        return jdbcClient.sql(
            """
            SELECT EXISTS (
                SELECT 1
                FROM collab_photo_likes active
                JOIN collab_photo_likes trashed ON trashed.id = :resourceId
                WHERE active.id <> trashed.id
                  AND active.collab_session_id = trashed.collab_session_id
                  AND active.photo_id = trashed.photo_id
                  AND active.collab_guest_id = trashed.collab_guest_id
                  AND active.deleted_at IS NULL
            )
            """.trimIndent(),
        )
            .param("resourceId", row.resourceId)
            .query { rs, _ -> rs.getBoolean(1) }
            .single()
    }

    /**
     * 제품 좋아요 생성 경로와 동일한 collab_photo 행을 잠근다. 이후 충돌 확인과 복원을 같은
     * 트랜잭션에서 실행하면, 하객 재좋아요가 검사와 UPDATE 사이에 끼어들 수 없다.
     */
    fun lockCollabPhotoLikeIdentity(row: ChildTrashRow): Boolean {
        if (row.type != AdminChildTrashType.COLLAB_LIKE) return true
        return jdbcClient.sql(
            """
            SELECT p.id
            FROM collab_photo_likes trashed
            JOIN photos p ON p.id = trashed.photo_id
            WHERE trashed.id = :resourceId
              AND trashed.collab_session_id = :parentId
            FOR UPDATE OF p
            """.trimIndent(),
        )
            .param("resourceId", row.resourceId)
            .param("parentId", row.parentId)
            .query { rs, _ -> rs.getLong("id") }
            .optional()
            .isPresent
    }

    fun bumpParentVersion(type: AdminChildTrashType, parentId: Long, expectedVersion: Long): Int {
        val parentTable = mapping(type).parentTable
        return jdbcClient.sql(
            """
            UPDATE $parentTable
            SET version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :parentId AND version = :expectedVersion AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("parentId", parentId)
            .param("expectedVersion", expectedVersion)
            .update()
    }

    fun create(
        type: AdminChildTrashType,
        resourceId: Long,
        parentId: Long,
        actorAdminId: Long,
        reason: String,
        deletedAt: ZonedDateTime,
        restoreUntil: ZonedDateTime,
    ): Long {
        val canonicalReason = auditSanitizer.sanitizeStoredReason(reason)
        return jdbcClient.sql(
            """
            INSERT INTO admin_child_trash_records (
                resource_type, resource_id, parent_type, parent_id, actor_admin_id,
                actor_username, reason, status, deleted_at, restore_until, created_at, updated_at
            )
            VALUES (
                :resourceType, :resourceId, :parentType, :parentId, :actorAdminId,
                left('ADMIN #' || CAST(:actorAdminId AS TEXT), 64),
                :reason, 'ACTIVE', :deletedAt, :restoreUntil, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            RETURNING id
            """.trimIndent(),
        )
            .param("resourceType", type.name)
            .param("resourceId", resourceId)
            .param("parentType", type.parentType.name)
            .param("parentId", parentId)
            .param("actorAdminId", actorAdminId)
            .param("reason", canonicalReason)
            .param("deletedAt", deletedAt.toOffsetDateTime())
            .param("restoreUntil", restoreUntil.toOffsetDateTime())
            .query { rs, _ -> rs.getLong("id") }
            .single()
    }

    fun markRestored(id: Long, restoredAt: ZonedDateTime): Int = jdbcClient.sql(
        """
        UPDATE admin_child_trash_records
        SET status = 'RESTORED', restored_at = :restoredAt, updated_at = CURRENT_TIMESTAMP,
            failure_code = NULL
        WHERE id = :id AND status = 'ACTIVE'
        """.trimIndent(),
    ).param("id", id).param("restoredAt", restoredAt.toOffsetDateTime()).update()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimExpired(now: ZonedDateTime, staleBefore: ZonedDateTime, limit: Int = 50): List<ChildTrashRow> =
        jdbcClient.sql(
            """
            WITH candidates AS (
                SELECT id
                FROM admin_child_trash_records
                WHERE (status = 'ACTIVE' AND restore_until <= :now
                       AND (next_purge_attempt_at IS NULL OR next_purge_attempt_at <= :now))
                   OR (status = 'PURGING' AND purge_started_at < :staleBefore)
                ORDER BY COALESCE(next_purge_attempt_at, restore_until), restore_until, id
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
            ), claimed AS (
                UPDATE admin_child_trash_records r
                SET status = 'PURGING', purge_attempt_count = r.purge_attempt_count + 1,
                    purge_started_at = :now, next_purge_attempt_at = NULL,
                    failure_code = NULL, updated_at = CURRENT_TIMESTAMP
                FROM candidates c
                WHERE r.id = c.id
                RETURNING r.*
            )
            SELECT id, resource_type, resource_id, parent_type, parent_id, actor_admin_id,
                   actor_username, reason, status, deleted_at, restore_until, restored_at,
                   purged_at, purge_attempt_count, purge_started_at, failure_code
            FROM claimed
            ORDER BY restore_until, id
            """.trimIndent(),
        )
            .param("now", now.toOffsetDateTime())
            .param("staleBefore", staleBefore.toOffsetDateTime())
            .param("limit", limit)
            .query { rs, _ -> row(rs) }
            .list()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markPurgeFailed(
        id: Long,
        failureCode: String,
        nextAttemptAt: ZonedDateTime,
        maxAttempts: Int,
    ) {
        jdbcClient.sql(
            """
            UPDATE admin_child_trash_records
            SET status = CASE
                    WHEN purge_attempt_count >= :maxAttempts THEN 'PURGE_FAILED'
                    ELSE 'ACTIVE'
                END,
                purge_started_at = NULL,
                next_purge_attempt_at = CASE
                    WHEN purge_attempt_count >= :maxAttempts THEN NULL
                    ELSE :nextAttemptAt
                END,
                failure_code = :failureCode,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PURGING'
            """.trimIndent(),
        )
            .param("id", id)
            .param("failureCode", failureCode.take(120))
            .param("nextAttemptAt", nextAttemptAt.toOffsetDateTime())
            .param("maxAttempts", maxAttempts)
            .update()
    }

    fun storageKeys(row: ChildTrashRow): List<String> {
        if (row.type != AdminChildTrashType.RETOUCH_ITEM) return emptyList()
        return jdbcClient.sql(
            """
            WITH candidate_keys AS (
                SELECT annotation_key AS storage_key FROM retouch_photos
                WHERE id = :resourceId AND deleted_at = :deletedAt
                UNION ALL
                SELECT result_key AS storage_key FROM retouch_photos
                WHERE id = :resourceId AND deleted_at = :deletedAt
                UNION ALL
                SELECT u.storage_key
                FROM admin_retouch_artifact_uploads u
                JOIN retouch_photos rp ON rp.id = u.retouch_photo_id
                WHERE rp.id = :resourceId AND rp.deleted_at = :deletedAt
            ), expected_preview_keys AS (
                SELECT 'previews/' || regexp_replace(storage_key, '\.[^.]*$', '') || '.jpg' AS storage_key
                FROM photos
                UNION
                SELECT 'previews/' || regexp_replace(storage_key, '\.[^.]*$', '') || '.jpg'
                FROM admin_photo_revisions
                UNION
                SELECT 'previews/' || regexp_replace(storage_key, '\.[^.]*$', '') || '.jpg'
                FROM admin_photo_replacement_uploads
            )
            SELECT DISTINCT candidate.storage_key
            FROM candidate_keys candidate
            WHERE candidate.storage_key IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM retouch_photos other
                  WHERE other.id <> :resourceId
                    AND (
                        other.annotation_key = candidate.storage_key
                        OR other.result_key = candidate.storage_key
                    )
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM admin_retouch_artifact_uploads other
                  WHERE other.retouch_photo_id <> :resourceId
                    AND other.storage_key = candidate.storage_key
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM photos other
                  WHERE other.storage_key = candidate.storage_key
                     OR other.preview_key = candidate.storage_key
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM admin_photo_revisions other
                  WHERE other.storage_key = candidate.storage_key
                     OR other.preview_key = candidate.storage_key
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM admin_photo_replacement_uploads other
                  WHERE other.storage_key = candidate.storage_key
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM expected_preview_keys other
                  WHERE other.storage_key = candidate.storage_key
              )
            """.trimIndent(),
        )
            .param("resourceId", row.resourceId)
            .param("deletedAt", row.deletedAt.toOffsetDateTime())
            .query { rs, _ -> rs.getString("storage_key") }
            .list()
    }

    fun deleteChild(row: ChildTrashRow): Int {
        val childTable = mapping(row.type).childTable
        return jdbcClient.sql("DELETE FROM $childTable WHERE id = :resourceId AND deleted_at = :deletedAt")
            .param("resourceId", row.resourceId)
            .param("deletedAt", row.deletedAt.toOffsetDateTime())
            .update()
    }

    fun childExists(row: ChildTrashRow): Boolean {
        val childTable = mapping(row.type).childTable
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM $childTable WHERE id = :resourceId)")
            .param("resourceId", row.resourceId)
            .query { rs, _ -> rs.getBoolean(1) }
            .single()
    }

    fun markPurged(id: Long, purgedAt: ZonedDateTime): Int = jdbcClient.sql(
        """
        UPDATE admin_child_trash_records
        SET status = 'PURGED', purged_at = :purgedAt, purge_started_at = NULL,
            next_purge_attempt_at = NULL,
            actor_username = NULL,
            reason = 'reasonCategory=UNSPECIFIED operatorReasonProvided=false',
            failure_code = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'PURGING'
        """.trimIndent(),
    ).param("id", id).param("purgedAt", purgedAt.toOffsetDateTime()).update()

    private fun row(rs: ResultSet): ChildTrashRow = ChildTrashRow(
        id = rs.getLong("id"),
        type = AdminChildTrashType.valueOf(rs.getString("resource_type")),
        resourceId = rs.getLong("resource_id"),
        parentType = AdminResourceType.valueOf(rs.getString("parent_type")),
        parentId = rs.getLong("parent_id"),
        actorAdminId = rs.getLong("actor_admin_id").takeUnless { rs.wasNull() },
        actorUsername = rs.getString("actor_username"),
        reason = rs.getString("reason"),
        status = rs.getString("status"),
        deletedAt = rs.getObject("deleted_at", OffsetDateTime::class.java).toZonedDateTime(),
        restoreUntil = rs.getObject("restore_until", OffsetDateTime::class.java).toZonedDateTime(),
        restoredAt = rs.getObject("restored_at", OffsetDateTime::class.java)?.toZonedDateTime(),
        purgedAt = rs.getObject("purged_at", OffsetDateTime::class.java)?.toZonedDateTime(),
        purgeAttemptCount = rs.getInt("purge_attempt_count"),
        purgeStartedAt = rs.getObject("purge_started_at", OffsetDateTime::class.java)?.toZonedDateTime(),
        failureCode = rs.getString("failure_code"),
    )

    private fun mapping(type: AdminChildTrashType): Mapping = when (type) {
        AdminChildTrashType.COLLAB_COMMENT -> Mapping(
            childTable = "collab_photo_comments",
            parentTable = "collab_sessions",
            childParentPredicate = "r.collab_session_id = :parentId",
        )
        AdminChildTrashType.COLLAB_LIKE -> Mapping(
            childTable = "collab_photo_likes",
            parentTable = "collab_sessions",
            childParentPredicate = "r.collab_session_id = :parentId",
        )
        AdminChildTrashType.ALBUM_TEMPLATE -> Mapping(
            childTable = "admin_album_templates",
            parentTable = "photo_folder_groups",
            childParentPredicate =
                """
                EXISTS (
                    SELECT 1
                    FROM photo_folder_groups parent
                    JOIN galleries gallery ON gallery.id = parent.gallery_id
                    WHERE parent.id = :parentId AND gallery.workspace_id = r.studio_id
                )
                AND NOT EXISTS (
                    SELECT 1 FROM photo_folder_groups reference WHERE reference.template_id = r.id
                )
                """.trimIndent(),
        )
        AdminChildTrashType.RETOUCH_ITEM -> Mapping(
            childTable = "retouch_photos",
            parentTable = "retouch_rounds",
            childParentPredicate = "r.round_id = :parentId",
        )
    }

    data class ParentState(val version: Long, val deleted: Boolean)

    data class ChildTrashRow(
        val id: Long,
        val type: AdminChildTrashType,
        val resourceId: Long,
        val parentType: AdminResourceType,
        val parentId: Long,
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

    private data class Mapping(
        val childTable: String,
        val parentTable: String,
        val childParentPredicate: String,
    )
}
