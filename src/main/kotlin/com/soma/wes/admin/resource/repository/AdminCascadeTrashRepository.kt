package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.audit.support.AdminAuditSanitizer
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.trash.repository.purgeObjectKeysSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import tools.jackson.databind.ObjectMapper

@Repository
class AdminCascadeTrashRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val auditSanitizer: AdminAuditSanitizer,
) {

    /**
     * product purge와 겹칠 수 있는 gallery/photo를 advisory mutex로 먼저 직렬화한다.
     * soft-delete UPDATE는 이미 휴지통인 descendant를 잠그지 않으므로 이 별도 경계가 필요하다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockProductPurgeCoordinationScope(rootType: AdminResourceType, rootId: Long) {
        val galleryIds = when (rootType) {
            AdminResourceType.USER -> jdbcClient.sql(
                """
                SELECT g.id FROM galleries g
                JOIN studios s ON s.id = g.studio_id
                WHERE s.user_id = :rootId ORDER BY g.id
                """.trimIndent(),
            )
                .param("rootId", rootId)
                .query { rs, _ -> rs.getLong("id") }
                .list()
            AdminResourceType.STUDIO -> jdbcClient.sql(
                "SELECT id FROM galleries WHERE studio_id = :rootId ORDER BY id",
            )
                .param("rootId", rootId)
                .query { rs, _ -> rs.getLong("id") }
                .list()
            AdminResourceType.GALLERY -> listOf(rootId)
            else -> emptyList()
        }
        galleryIds.distinct().sorted().forEach { galleryId ->
            lockProductPurgeCoordination("GALLERY", galleryId)
        }

        val photoIds = when (rootType) {
            AdminResourceType.USER -> jdbcClient.sql(
                """
                SELECT p.id FROM photos p
                JOIN galleries g ON g.id = p.gallery_id
                JOIN studios s ON s.id = g.studio_id
                WHERE s.user_id = :rootId ORDER BY p.id
                """.trimIndent(),
            )
                .param("rootId", rootId)
                .query { rs, _ -> rs.getLong("id") }
                .list()
            AdminResourceType.STUDIO -> jdbcClient.sql(
                """
                SELECT p.id FROM photos p
                JOIN galleries g ON g.id = p.gallery_id
                WHERE g.studio_id = :rootId ORDER BY p.id
                """.trimIndent(),
            )
                .param("rootId", rootId)
                .query { rs, _ -> rs.getLong("id") }
                .list()
            AdminResourceType.GALLERY -> jdbcClient.sql(
                "SELECT id FROM photos WHERE gallery_id = :rootId ORDER BY id",
            )
                .param("rootId", rootId)
                .query { rs, _ -> rs.getLong("id") }
                .list()
            AdminResourceType.PHOTO -> listOf(rootId)
            AdminResourceType.SELECTION -> relatedPhotoIds("photo_selection_items", "selection_id", rootId)
            AdminResourceType.COLLABORATION -> jdbcClient.sql(
                "SELECT photo_id AS id FROM collab_photos WHERE collab_session_id = :rootId ORDER BY photo_id",
            )
                .param("rootId", rootId)
                .query { rs, _ -> rs.getLong("id") }
                .list()
            AdminResourceType.ALBUM -> relatedPhotoIds("photo_folder_items", "group_id", rootId)
            AdminResourceType.RETOUCH_REQUEST -> jdbcClient.sql(
                "SELECT photo_id AS id FROM retouch_photos WHERE round_id = :rootId ORDER BY photo_id",
            )
                .param("rootId", rootId)
                .query { rs, _ -> rs.getLong("id") }
                .list()
        }
        photoIds.distinct().sorted().forEach { photoId ->
            lockProductPurgeCoordination("PHOTO", photoId)
        }
    }

    fun createBatch(
        actorAdminId: Long,
        rootType: AdminResourceType,
        rootId: Long,
        rootLabel: String,
        reason: String,
        deletedAt: ZonedDateTime,
        restoreUntil: ZonedDateTime,
    ): Long {
        val canonicalRootLabel = requireNotNull(
            auditSanitizer.canonicalTargetLabel(rootType.auditTargetType, rootId.toString(), rootLabel),
        )
        val canonicalReason = auditSanitizer.sanitizeStoredReason(reason)
        return jdbcClient.sql(
            """
            INSERT INTO admin_trash_batches (
                root_type, root_id, root_label, actor_admin_id, actor_username, reason,
                status, deleted_at, restore_until, created_at, updated_at
            )
            VALUES (
                :rootType, :rootId, :rootLabel, :actorAdminId,
                left('ADMIN #' || CAST(:actorAdminId AS TEXT), 64), :reason,
                'ACTIVE', :deletedAt, :restoreUntil, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            RETURNING id
            """.trimIndent(),
        )
            .param("rootType", rootType.name)
            .param("rootId", rootId)
            .param("rootLabel", canonicalRootLabel)
            .param("actorAdminId", actorAdminId)
            .param("reason", canonicalReason)
            .param("deletedAt", deletedAt.toOffsetDateTime())
            .param("restoreUntil", restoreUntil.toOffsetDateTime())
            .query { rs, _ -> rs.getLong("id") }
            .single()
    }

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
                INSERT INTO admin_trash_entries (
                    batch_id, resource_type, resource_id, is_root, relation_path, created_at
                )
                SELECT :batchId, :resourceType, id,
                       (:resourceType = :rootResourceType AND id = :rootId),
                       CASE
                           WHEN :resourceType = :rootResourceType AND id = :rootId THEN 'ROOT'
                           ELSE :relationPath
                       END,
                       CURRENT_TIMESTAMP
                FROM affected
                """.trimIndent(),
            )
                .param("deletedAt", deletedAt.toOffsetDateTime())
                .param("rootId", rootId)
                .param("batchId", batchId)
                .param("resourceType", target.type)
                .param("rootResourceType", rootType.name)
                .param("relationPath", "${rootType.name}>${target.type}")
                .update()
        }
        return affectedCounts(batchId)
    }

    /**
     * 제품 purge가 S3를 지우는 동안 남기는 영속 claim과 이 배치의 실제 entry가 겹치는지
     * 확인한다. lease가 만료됐더라도 S3/DB 사이 crash 상태일 수 있으므로 admin은 모든 claim을
     * hard conflict로 취급한다. 호출자는 moveToTrash와 같은 트랜잭션에서 검사해야 한다.
     */
    fun hasProductPurgeClaimForBatch(batchId: Long): Boolean = jdbcClient.sql(
        """
        SELECT EXISTS (
            SELECT 1
            FROM product_purge_claims claim
            WHERE (
                claim.resource_type = 'GALLERY'
                AND (
                    EXISTS (
                        SELECT 1
                        FROM admin_trash_entries e
                        WHERE e.batch_id = :batchId
                          AND (
                          (e.resource_type = 'GALLERY' AND e.resource_id = claim.resource_id)
                          OR (e.resource_type = 'GALLERY_MEMBER' AND EXISTS (
                              SELECT 1 FROM gallery_members x
                              WHERE x.id = e.resource_id AND x.gallery_id = claim.resource_id
                          ))
                          OR (e.resource_type = 'PHOTO' AND EXISTS (
                              SELECT 1 FROM photos x
                              WHERE x.id = e.resource_id AND x.gallery_id = claim.resource_id
                          ))
                          OR (e.resource_type = 'SELECTION' AND EXISTS (
                              SELECT 1 FROM photo_selections x
                              WHERE x.id = e.resource_id AND x.gallery_id = claim.resource_id
                          ))
                          OR (e.resource_type = 'COLLABORATION' AND EXISTS (
                              SELECT 1 FROM collab_sessions x
                              WHERE x.id = e.resource_id AND x.gallery_id = claim.resource_id
                          ))
                          OR (e.resource_type = 'COLLAB_COMMENT' AND EXISTS (
                              SELECT 1
                              FROM collab_photo_comments x
                              JOIN collab_photos p ON p.id = x.collab_photo_id
                              JOIN collab_sessions s ON s.id = p.collab_session_id
                              WHERE x.id = e.resource_id AND s.gallery_id = claim.resource_id
                          ))
                          OR (e.resource_type = 'ALBUM' AND EXISTS (
                              SELECT 1 FROM photo_folder_groups x
                              WHERE x.id = e.resource_id AND x.gallery_id = claim.resource_id
                          ))
                          OR (e.resource_type = 'RETOUCH_REQUEST' AND EXISTS (
                              SELECT 1 FROM retouch_rounds x
                              WHERE x.id = e.resource_id AND x.gallery_id = claim.resource_id
                          ))
                          )
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM admin_trash_batches b
                        LEFT JOIN galleries g ON g.id = claim.resource_id
                        LEFT JOIN studios s ON s.id = g.studio_id
                        WHERE b.id = :batchId
                          AND (
                              (b.root_type = 'GALLERY' AND b.root_id = claim.resource_id)
                              OR (b.root_type = 'STUDIO' AND b.root_id = g.studio_id)
                              OR (b.root_type = 'USER' AND b.root_id = s.user_id)
                          )
                    )
                )
            )
            OR (
                claim.resource_type = 'PHOTO'
                AND (
                    EXISTS (
                        SELECT 1
                        FROM admin_trash_entries e
                        WHERE e.batch_id = :batchId
                          AND (
                          (e.resource_type = 'PHOTO' AND e.resource_id = claim.resource_id)
                          OR (e.resource_type = 'SELECTION' AND EXISTS (
                              SELECT 1 FROM photo_selection_items x
                              WHERE x.selection_id = e.resource_id AND x.photo_id = claim.resource_id
                          ))
                          OR (e.resource_type = 'COLLABORATION' AND EXISTS (
                              SELECT 1 FROM collab_photos x
                              WHERE x.collab_session_id = e.resource_id AND x.photo_id = claim.resource_id
                          ))
                          OR (e.resource_type = 'COLLAB_COMMENT' AND EXISTS (
                              SELECT 1
                              FROM collab_photo_comments x
                              JOIN collab_photos p ON p.id = x.collab_photo_id
                              WHERE x.id = e.resource_id AND p.photo_id = claim.resource_id
                          ))
                          OR (e.resource_type = 'ALBUM' AND EXISTS (
                              SELECT 1 FROM photo_folder_items x
                              WHERE x.group_id = e.resource_id AND x.photo_id = claim.resource_id
                          ))
                          OR (e.resource_type = 'RETOUCH_REQUEST' AND EXISTS (
                              SELECT 1 FROM retouch_photos x
                              WHERE x.round_id = e.resource_id AND x.photo_id = claim.resource_id
                          ))
                          )
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM admin_trash_batches b
                        LEFT JOIN photos p ON p.id = claim.resource_id
                        LEFT JOIN galleries g ON g.id = p.gallery_id
                        LEFT JOIN studios s ON s.id = g.studio_id
                        WHERE b.id = :batchId
                          AND (
                              (b.root_type = 'GALLERY' AND b.root_id = g.id)
                              OR (b.root_type = 'STUDIO' AND b.root_id = g.studio_id)
                              OR (b.root_type = 'USER' AND b.root_id = s.user_id)
                          )
                    )
                )
            )
        )
        """.trimIndent(),
    )
        .param("batchId", batchId)
        .query { rs, _ -> rs.getBoolean(1) }
        .single()

    fun captureRelationshipFacts(batchId: Long): Map<String, Long> {
        val facts = linkedMapOf(
            "entryCount" to scalar(
                "SELECT COUNT(*) FROM admin_trash_entries WHERE batch_id = :batchId",
                batchId,
            ),
            "rootEntryCount" to scalar(
                """
                SELECT COUNT(*)
                FROM admin_trash_entries e
                JOIN admin_trash_batches b ON b.id = e.batch_id
                WHERE e.batch_id = :batchId
                  AND (e.is_root OR (e.resource_type = b.root_type AND e.resource_id = b.root_id))
                """.trimIndent(),
                batchId,
            ),
            "commentCount" to scalar(
                "SELECT COUNT(*) FROM admin_trash_entries WHERE batch_id = :batchId AND resource_type = 'COLLAB_COMMENT'",
                batchId,
            ),
            "albumTemplateReferenceCount" to scalar(
                """
                SELECT COUNT(*)
                FROM admin_trash_entries e
                JOIN photo_folder_groups a ON a.id = e.resource_id
                WHERE e.batch_id = :batchId AND e.resource_type = 'ALBUM'
                  AND (a.template_id IS NOT NULL OR a.template_name IS NOT NULL)
                """.trimIndent(),
                batchId,
            ),
            "templateMetadataCount" to scalar(
                """
                SELECT COUNT(DISTINCT t.id)
                FROM admin_trash_entries e
                JOIN photo_folder_groups a ON a.id = e.resource_id
                JOIN admin_album_templates t ON t.id = a.template_id
                WHERE e.batch_id = :batchId AND e.resource_type = 'ALBUM'
                """.trimIndent(),
                batchId,
            ),
            "entityRevisionCount" to scalar(
                """
                SELECT COUNT(*)
                FROM admin_entity_revisions r
                JOIN admin_trash_entries e
                  ON e.resource_type = r.target_type AND e.resource_id::TEXT = r.target_id
                WHERE e.batch_id = :batchId
                """.trimIndent(),
                batchId,
            ),
            "photoRevisionCount" to scalar(
                """
                SELECT COUNT(*)
                FROM admin_photo_revisions r
                JOIN admin_trash_entries e
                  ON e.resource_type = 'PHOTO' AND e.resource_id = r.photo_id
                WHERE e.batch_id = :batchId
                """.trimIndent(),
                batchId,
            ),
            "selectionRevisionCount" to scalar(
                """
                SELECT COUNT(*)
                FROM admin_selection_revisions r
                JOIN admin_trash_entries e
                  ON e.resource_type = 'SELECTION' AND e.resource_id = r.selection_id
                WHERE e.batch_id = :batchId
                """.trimIndent(),
                batchId,
            ),
            "photoStorageMetadataCount" to scalar(
                """
                SELECT
                    (SELECT COUNT(*) FROM photos p JOIN admin_trash_entries e
                       ON e.resource_type = 'PHOTO' AND e.resource_id = p.id
                     WHERE e.batch_id = :batchId)
                  + (SELECT COUNT(*) FROM admin_photo_revisions r JOIN admin_trash_entries e
                       ON e.resource_type = 'PHOTO' AND e.resource_id = r.photo_id
                     WHERE e.batch_id = :batchId)
                  + (SELECT COUNT(*) FROM admin_photo_replacement_uploads u JOIN admin_trash_entries e
                       ON e.resource_type = 'PHOTO' AND e.resource_id = u.photo_id
                     WHERE e.batch_id = :batchId)
                """.trimIndent(),
                batchId,
            ),
            "retouchStorageMetadataCount" to scalar(
                """
                SELECT
                    COALESCE((
                        SELECT SUM(
                            CASE WHEN rp.annotation_key IS NULL THEN 0 ELSE 1 END
                            + CASE WHEN rp.result_key IS NULL THEN 0 ELSE 1 END
                        )
                        FROM retouch_photos rp
                        WHERE EXISTS (
                            SELECT 1 FROM admin_trash_entries e
                            WHERE e.batch_id = :batchId
                              AND (
                                  (e.resource_type = 'RETOUCH_REQUEST' AND e.resource_id = rp.round_id)
                                  OR (e.resource_type = 'PHOTO' AND e.resource_id = rp.photo_id)
                              )
                        )
                    ), 0)
                    + (
                        SELECT COUNT(*)
                        FROM admin_retouch_artifact_uploads u
                        JOIN retouch_photos rp ON rp.id = u.retouch_photo_id
                        WHERE EXISTS (
                            SELECT 1 FROM admin_trash_entries e
                            WHERE e.batch_id = :batchId
                              AND (
                                  (e.resource_type = 'RETOUCH_REQUEST' AND e.resource_id = rp.round_id)
                                  OR (e.resource_type = 'PHOTO' AND e.resource_id = rp.photo_id)
                              )
                        )
                    )
                """.trimIndent(),
                batchId,
            ),
        )
        jdbcClient.sql(
            """
            UPDATE admin_trash_batches
            SET relationship_facts = CAST(:facts AS JSONB), updated_at = CURRENT_TIMESTAMP
            WHERE id = :batchId
            """.trimIndent(),
        )
            .param("facts", objectMapper.writeValueAsString(facts))
            .param("batchId", batchId)
            .update()
        return facts
    }

    fun hasOverlappingActiveBatch(rootType: AdminResourceType, rootId: Long): Boolean {
        val predicate = overlapPredicate(rootType)
        return jdbcClient.sql(
            """
            SELECT COUNT(*)
            FROM admin_trash_batches b
            WHERE b.status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
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
               purge_attempt_count, purge_started_at, failure_code, relationship_facts
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
               purge_attempt_count, purge_started_at, failure_code, relationship_facts
        FROM admin_trash_batches
        WHERE root_type = :rootType AND root_id = :rootId
          AND status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
        """.trimIndent(),
    )
        .param("rootType", rootType.name)
        .param("rootId", rootId)
        .query { rs, _ -> batch(rs) }
        .optional()
        .orElse(null)

    fun findActiveContaining(type: AdminResourceType, id: Long): BatchRow? = jdbcClient.sql(
        """
        SELECT b.id, b.root_type, b.root_id, b.root_label, b.actor_admin_id, b.actor_username, b.reason,
               b.status, b.deleted_at, b.restore_until, b.restored_at, b.purged_at,
               b.purge_attempt_count, b.purge_started_at, b.failure_code, b.relationship_facts
        FROM admin_trash_entries e
        JOIN admin_trash_batches b ON b.id = e.batch_id
        WHERE e.resource_type = :resourceType AND e.resource_id = :resourceId
          AND b.status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
          AND NOT (b.root_type = e.resource_type AND b.root_id = e.resource_id)
        ORDER BY b.deleted_at DESC, b.id DESC
        LIMIT 1
        """.trimIndent(),
    )
        .param("resourceType", type.name)
        .param("resourceId", id)
        .query { rs, _ -> batch(rs) }
        .optional()
        .orElse(null)

    fun list(limit: Int = 200): List<BatchRow> = jdbcClient.sql(
        """
        SELECT id, root_type, root_id, root_label, actor_admin_id, actor_username, reason,
               status, deleted_at, restore_until, restored_at, purged_at,
               purge_attempt_count, purge_started_at, failure_code, relationship_facts
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

    fun entries(batchId: Long): List<TrashEntryRow> = jdbcClient.sql(
        """
        SELECT e.resource_type, e.resource_id,
               (e.is_root OR (e.resource_type = b.root_type AND e.resource_id = b.root_id)) AS effective_root,
               COALESCE(
                   e.relation_path,
                   CASE
                       WHEN e.resource_type = b.root_type AND e.resource_id = b.root_id THEN 'ROOT'
                       ELSE b.root_type || '>' || e.resource_type
                   END
               ) AS effective_relation_path
        FROM admin_trash_entries e
        JOIN admin_trash_batches b ON b.id = e.batch_id
        WHERE e.batch_id = :batchId
        ORDER BY effective_root DESC, e.resource_type, e.resource_id
        """.trimIndent(),
    )
        .param("batchId", batchId)
        .query { rs, _ ->
            TrashEntryRow(
                resourceType = rs.getString("resource_type"),
                resourceId = rs.getLong("resource_id"),
                root = rs.getBoolean("effective_root"),
                relationPath = rs.getString("effective_relation_path"),
            )
        }
        .list()

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
                WHERE (status = 'ACTIVE' AND restore_until <= :now
                       AND (next_purge_attempt_at IS NULL OR next_purge_attempt_at <= :now))
                   OR (status = 'PURGING' AND purge_started_at < :staleBefore)
                ORDER BY COALESCE(next_purge_attempt_at, restore_until), restore_until, id
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
            ), claimed AS (
                UPDATE admin_trash_batches b
                SET status = 'PURGING', purge_attempt_count = b.purge_attempt_count + 1,
                    purge_started_at = :now, next_purge_attempt_at = NULL,
                    failure_code = NULL, updated_at = CURRENT_TIMESTAMP
                FROM candidates c
                WHERE b.id = c.id
                RETURNING b.*
            )
            SELECT id, root_type, root_id, root_label, actor_admin_id, actor_username, reason,
                   status, deleted_at, restore_until, restored_at, purged_at,
                   purge_attempt_count, purge_started_at, failure_code, relationship_facts
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
    fun markPurgeFailed(
        batchId: Long,
        failureCode: String,
        nextAttemptAt: ZonedDateTime,
        maxAttempts: Int,
    ) {
        jdbcClient.sql(
            """
            UPDATE admin_trash_batches
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
            WHERE id = :batchId AND status = 'PURGING'
            """.trimIndent(),
        )
            .param("batchId", batchId)
            .param("failureCode", failureCode.take(120))
            .param("nextAttemptAt", nextAttemptAt.toOffsetDateTime())
            .param("maxAttempts", maxAttempts)
            .update()
    }

    fun findPurgeObjectKeys(batch: BatchRow): List<String> {
        val galleryPredicate = galleryScopePredicate(batch.rootType)
        val photoPredicate = when (batch.rootType) {
            AdminResourceType.PHOTO -> "p.id = :rootId"
            else -> galleryPredicate?.let { "p.gallery_id IN (SELECT g.id FROM galleries g WHERE $it)" }
        }
        val retouchPredicate = when (batch.rootType) {
            AdminResourceType.PHOTO -> "rp.photo_id = :rootId"
            AdminResourceType.RETOUCH_REQUEST -> "rp.round_id = :rootId"
            else -> galleryPredicate?.let { "rp.gallery_id IN (SELECT g.id FROM galleries g WHERE $it)" }
        }
        if (photoPredicate == null && retouchPredicate == null) return emptyList()

        return jdbcClient.sql(
            purgeObjectKeysSql(
                photoPredicate = photoPredicate ?: "FALSE",
                retouchPhotoPredicate = retouchPredicate ?: "FALSE",
            ),
        )
            .param("rootId", batch.rootId)
            .query { rs, _ -> rs.getString("storage_key") }
            .list()
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

    fun cancelPendingOperations(batchId: Long): Int {
        val idempotency = jdbcClient.sql(
            """
            UPDATE admin_idempotency_keys k
            SET status = 'CANCELED', updated_at = CURRENT_TIMESTAMP
            WHERE k.status IN ('PENDING', 'FAILED')
              AND EXISTS (
                  SELECT 1 FROM admin_trash_entries e
                  WHERE e.batch_id = :batchId
                    AND e.resource_type = k.target_type
                    AND e.resource_id::TEXT = k.target_id
              )
            """.trimIndent(),
        ).param("batchId", batchId).update()
        val processingJobs = jdbcClient.sql(
            """
            UPDATE admin_processing_jobs j
            SET status = 'CANCELED', failure_code = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE j.status IN ('PENDING', 'DISPATCHING', 'DISPATCHED', 'FAILED')
              AND EXISTS (
                  SELECT 1 FROM admin_trash_entries e
                  WHERE e.batch_id = :batchId
                    AND e.resource_type = j.target_type
                    AND e.resource_id = j.target_id
              )
            """.trimIndent(),
        ).param("batchId", batchId).update()
        val aiJobs = jdbcClient.sql(
            """
            UPDATE admin_ai_selection_jobs j
            SET status = 'CANCELED', completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE j.status IN ('PENDING', 'RUNNING', 'FAILED')
              AND EXISTS (
                  SELECT 1 FROM admin_trash_entries e
                  WHERE e.batch_id = :batchId
                    AND e.resource_type = 'SELECTION'
                    AND e.resource_id = j.selection_id
              )
            """.trimIndent(),
        ).param("batchId", batchId).update()
        val notifications = jdbcClient.sql(
            """
            UPDATE admin_notification_outbox n
            SET status = 'CANCELED', updated_at = CURRENT_TIMESTAMP
            WHERE n.status IN ('PENDING', 'SENDING', 'FAILED')
              AND EXISTS (
                  SELECT 1 FROM admin_trash_entries e
                  WHERE e.batch_id = :batchId
                    AND e.resource_type = n.source_type
                    AND e.resource_id = n.source_id
              )
            """.trimIndent(),
        ).param("batchId", batchId).update()
        val inboxNotifications = jdbcClient.sql(
            """
            UPDATE admin_notification_inbox n
            SET work_status = 'CANCELED', version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE n.work_status = 'OPEN'
              AND EXISTS (
                  SELECT 1 FROM admin_trash_entries e
                  WHERE e.batch_id = :batchId
                    AND e.resource_type = n.target_type
                    AND e.resource_id = n.target_id
              )
            """.trimIndent(),
        ).param("batchId", batchId).update()
        val replacementUploads = jdbcClient.sql(
            """
            UPDATE admin_photo_replacement_uploads u
            SET status = 'EXPIRED'
            WHERE u.status = 'PENDING'
              AND EXISTS (
                  SELECT 1 FROM admin_trash_entries e
                  WHERE e.batch_id = :batchId
                    AND e.resource_type = 'PHOTO'
                    AND e.resource_id = u.photo_id
              )
            """.trimIndent(),
        ).param("batchId", batchId).update()
        return idempotency + processingJobs + aiJobs + notifications + inboxNotifications + replacementUploads
    }

    /**
     * 다형 FK를 걸 수 없는 관리자 실행 기록은 배치가 확정한 type/id 쌍으로만 영구 삭제한다.
     * 완료·실패 상태도 payload를 보유하므로 상태와 무관하게 함께 걷는다.
     */
    fun deleteOperationalPayloads(batchId: Long): Int = jdbcClient.sql(
        """
        WITH targets AS MATERIALIZED (
            SELECT resource_type, resource_id
            FROM admin_trash_entries
            WHERE batch_id = :batchId
        ), deleted_idempotency AS (
            DELETE FROM admin_idempotency_keys k
            USING targets t
            WHERE k.target_type = t.resource_type
              AND k.target_id = t.resource_id::TEXT
            RETURNING 1
        ), deleted_processing_jobs AS (
            DELETE FROM admin_processing_jobs j
            USING targets t
            WHERE j.target_type = t.resource_type
              AND j.target_id = t.resource_id
            RETURNING 1
        ), deleted_notifications AS (
            DELETE FROM admin_notification_outbox n
            USING targets t
            WHERE n.source_type = t.resource_type
              AND n.source_id = t.resource_id
            RETURNING 1
        ), deleted_inbox_notifications AS (
            DELETE FROM admin_notification_inbox n
            USING targets t
            WHERE n.target_type = t.resource_type
              AND n.target_id = t.resource_id
            RETURNING 1
        )
        SELECT
            (SELECT COUNT(*) FROM deleted_idempotency)
            + (SELECT COUNT(*) FROM deleted_processing_jobs)
            + (SELECT COUNT(*) FROM deleted_notifications)
            + (SELECT COUNT(*) FROM deleted_inbox_notifications)
        """.trimIndent(),
    )
        .param("batchId", batchId)
        .query { rs, _ -> rs.getInt(1) }
        .single()

    fun expireRevisionRestorePayloads(batchId: Long): Int = jdbcClient.sql(
        """
        UPDATE admin_entity_revisions r
        SET before_restore_payload = NULL,
            after_restore_payload = NULL,
            expires_at = LEAST(r.restore_expires_at, CURRENT_TIMESTAMP),
            restore_expires_at = LEAST(r.restore_expires_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP
        WHERE EXISTS (
            SELECT 1 FROM admin_trash_entries e
            WHERE e.batch_id = :batchId
              AND e.resource_type = r.target_type
              AND e.resource_id::TEXT = r.target_id
        )
          AND (r.before_restore_payload IS NOT NULL OR r.after_restore_payload IS NOT NULL)
        """.trimIndent(),
    ).param("batchId", batchId).update()

    fun markPurged(batchId: Long): Int = jdbcClient.sql(
        """
        UPDATE admin_trash_batches
        SET status = 'PURGED', purged_at = CURRENT_TIMESTAMP, purge_started_at = NULL,
            next_purge_attempt_at = NULL,
            root_label = root_type || ' #' || root_id,
            actor_username = NULL,
            reason = 'reasonCategory=UNSPECIFIED operatorReasonProvided=false',
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

    private fun relatedPhotoIds(table: String, parentColumn: String, parentId: Long): List<Long> =
        jdbcClient.sql("SELECT photo_id AS id FROM $table WHERE $parentColumn = :parentId ORDER BY photo_id")
            .param("parentId", parentId)
            .query { rs, _ -> rs.getLong("id") }
            .list()

    private fun lockProductPurgeCoordination(resourceType: String, resourceId: Long) {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:coordinationKey, 0))")
            .param("coordinationKey", "WES_PRODUCT_PURGE:$resourceType:$resourceId")
            .query { _, _ -> Unit }
            .single()
    }

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
        relationshipFacts = relationshipFacts(rs),
    )

    private fun relationshipFacts(rs: ResultSet): Map<String, Long> {
        val node = objectMapper.readTree(rs.getString("relationship_facts"))
        return FACT_NAMES.associateWith { name -> node.path(name).asLong() }
    }

    private fun scalar(sql: String, batchId: Long): Long = jdbcClient.sql(sql)
        .param("batchId", batchId)
        .query { rs, _ -> rs.getLong(1) }
        .single()

    private fun dateTime(rs: ResultSet, column: String): ZonedDateTime? =
        rs.getObject(column, OffsetDateTime::class.java)?.toZonedDateTime()

    private fun scopePredicate(root: AdminResourceType, target: String): String? = when (root) {
        AdminResourceType.USER -> when (target) {
            "USER" -> "r.id = :rootId"
            "STUDIO_MEMBER" -> """
                r.user_id = :rootId
                OR r.studio_id IN (SELECT s.id FROM studios s WHERE s.user_id = :rootId)
            """.trimIndent()
            "GALLERY_MEMBER" -> """
                r.user_id = :rootId
                OR r.gallery_id IN (
                    SELECT g.id
                    FROM galleries g
                    WHERE g.studio_id IN (SELECT s.id FROM studios s WHERE s.user_id = :rootId)
                )
            """.trimIndent()
            "STUDIO" -> "r.user_id = :rootId"
            "GALLERY" -> "r.studio_id IN (SELECT s.id FROM studios s WHERE s.user_id = :rootId)"
            else -> childGalleryPredicate(target, "g.studio_id IN (SELECT s.id FROM studios s WHERE s.user_id = :rootId)")
        }
        AdminResourceType.STUDIO -> when (target) {
            "STUDIO" -> "r.id = :rootId"
            "STUDIO_MEMBER" -> "r.studio_id = :rootId"
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
        "GALLERY_MEMBER" ->
            "r.gallery_id IN (SELECT g.id FROM galleries g WHERE $galleryPredicate)"
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
            OR (b.root_type = 'STUDIO' AND b.root_id IN (SELECT sm.studio_id FROM studio_members sm WHERE sm.user_id = :rootId))
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
        if (includeStudio) add("(b.root_type = 'USER' AND b.root_id IN (SELECT s.user_id FROM studios s WHERE s.id = :rootId))")
        if (includeStudio) add("(b.root_type = 'USER' AND b.root_id IN (SELECT sm.user_id FROM studio_members sm WHERE sm.studio_id = :rootId))")
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
        val relationshipFacts: Map<String, Long>,
    )

    data class TrashEntryRow(
        val resourceType: String,
        val resourceId: Long,
        val root: Boolean,
        val relationPath: String,
    )

    private data class SoftTarget(val type: String, val table: String)

    companion object {
        private val FACT_NAMES = listOf(
            "entryCount",
            "rootEntryCount",
            "commentCount",
            "albumTemplateReferenceCount",
            "templateMetadataCount",
            "entityRevisionCount",
            "photoRevisionCount",
            "selectionRevisionCount",
            "photoStorageMetadataCount",
            "retouchStorageMetadataCount",
        )
        private val SOFT_TARGETS = listOf(
            SoftTarget("USER", "users"),
            SoftTarget("STUDIO_MEMBER", "studio_members"),
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
