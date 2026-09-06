package com.soma.wes.trash.repository

import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.trash.repository.projection.TrashedGalleryRow
import com.soma.wes.trash.repository.projection.TrashedPhotoRow
import com.soma.wes.trash.repository.projection.TrashedPhotoTarget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 휴지통이 다루는 행 — `deleted_at`이 채워진 사진·갤러리 — 의 유일한 통로.
 *
 * JPA가 아니라 [JdbcClient]인 이유는 엔티티의 `@SQLRestriction`이다. 휴지통 행은 어떤 JPA
 * 조회에도 나타나지 않으므로(그것이 그 애노테이션의 존재 이유다), 목록·복원·물리 삭제는
 * 필터를 우회하는 네이티브 SQL이어야 한다. 반대로 이 클래스 밖의 네이티브 SQL은
 * `deleted_at IS NULL`을 직접 챙겨야 한다.
 *
 * 복원의 `deleted_at IS NOT NULL` 조건은 검증을 겸한다 — 갱신된 행 수가 요청과 다르면
 * 서비스가 예외를 던져 트랜잭션째 되돌린다.
 */
@Repository
class TrashRepository(
    private val jdbcClient: JdbcClient,
) {

    // --- 갤러리 ---

    /** Admin cascade와 product purge가 선행 soft-delete 행을 건너뛰어도 같은 mutex에서 직렬화한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockPurgeCoordinationForGallery(galleryId: Long) {
        lockPurgeCoordination("GALLERY", galleryId)
        jdbcClient.sql("SELECT id FROM photos WHERE gallery_id = :galleryId ORDER BY id")
            .param("galleryId", galleryId)
            .query { rs, _ -> rs.getLong("id") }
            .list()
            .forEach { photoId -> lockPurgeCoordination("PHOTO", photoId) }
    }

    fun findTrashedGalleries(workspaceIds: Collection<Long>): List<TrashedGalleryRow> =
        jdbcClient.sql(
            """
            SELECT g.id, g.title, g.deleted_at,
                   (SELECT count(*) FROM photos p WHERE p.gallery_id = g.id) AS photo_count
            FROM galleries g
            WHERE g.workspace_id IN (:workspaceIds)
              AND g.deleted_at IS NOT NULL
              AND ${notProtectedByAdminSql("g.id")}
              AND ${notClaimedForGallerySql("g.id")}
            ORDER BY g.deleted_at DESC, g.id DESC
            """.trimIndent(),
        )
            .param("workspaceIds", workspaceIds)
            .query { rs, _ ->
                TrashedGalleryRow(
                    galleryId = rs.getLong("id"),
                    title = rs.getString("title"),
                    photoCount = rs.getLong("photo_count"),
                    deletedAt = zonedDateTimeOf(rs, "deleted_at"),
                )
            }
            .list()

    /** 스튜디오 조건이 곧 인가다 — 갤러리가 숨어 있어 GalleryAccessPolicy를 지날 수 없다. */
    fun isTrashedGalleryOf(galleryId: Long, workspaceIds: Collection<Long>): Boolean =
        jdbcClient.sql(
            """
            SELECT count(*)
            FROM galleries g
            WHERE g.id = :galleryId
              AND g.workspace_id IN (:workspaceIds)
              AND g.deleted_at IS NOT NULL
              AND ${notProtectedByAdminSql("g.id")}
              AND ${notClaimedForGallerySql("g.id")}
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("workspaceIds", workspaceIds)
            .query { rs, _ -> rs.getLong(1) }
            .single() > 0

    /** 즉시 purge 사전 인가. 같은 대상의 만료 claim은 실행부가 lease를 확인해 재사용한다. */
    fun isTrashedGalleryOfForPurge(galleryId: Long, workspaceIds: Collection<Long>): Boolean =
        jdbcClient.sql(
            """
            SELECT count(*)
            FROM galleries g
            WHERE g.id = :galleryId
              AND g.workspace_id IN (:workspaceIds)
              AND g.deleted_at IS NOT NULL
              AND ${notProtectedByAdminSql("g.id")}
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("workspaceIds", workspaceIds)
            .query { rs, _ -> rs.getLong(1) }
            .single() > 0

    fun restoreGallery(galleryId: Long, workspaceIds: Collection<Long>): Int =
        jdbcClient.sql(
            """
            UPDATE galleries
            SET deleted_at = NULL,
                version = version + 1
            WHERE id = :galleryId
              AND workspace_id IN (:workspaceIds)
              AND deleted_at IS NOT NULL
              AND ${notProtectedByAdminSql("galleries.id")}
              AND ${notClaimedForGallerySql("galleries.id")}
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("workspaceIds", workspaceIds)
            .update()

    /**
     * 제품 갤러리 purge가 물리 삭제할 행을 관리자 cascade와 같은 순서로 잠근다.
     * 이 메서드는 짧은 claim/finalize 트랜잭션 안에서만 호출한다. S3 호출 중에는 잠금을
     * 유지하지 않고 영속 claim이 관리자 writer를 차단한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockGalleryPurgeScope(galleryId: Long): Boolean {
        // AdminCascadeTrashRepository.SOFT_TARGETS와 같은 GALLERY_MEMBER -> GALLERY 순서다.
        lockIds("SELECT id FROM gallery_members WHERE gallery_id = :galleryId ORDER BY id FOR UPDATE", galleryId)
        val galleryLocked = jdbcClient.sql(
            "SELECT id FROM galleries WHERE id = :galleryId AND deleted_at IS NOT NULL FOR UPDATE",
        )
            .param("galleryId", galleryId)
            .query { rs, _ -> rs.getLong("id") }
            .optional()
            .isPresent
        if (!galleryLocked) return false

        listOf(
            "SELECT id FROM photos WHERE gallery_id = :galleryId ORDER BY id FOR UPDATE",
            "SELECT id FROM photo_selections WHERE gallery_id = :galleryId ORDER BY id FOR UPDATE",
            "SELECT id FROM collab_sessions WHERE gallery_id = :galleryId ORDER BY id FOR UPDATE",
            "SELECT id FROM collab_session_photos WHERE gallery_id = :galleryId ORDER BY id FOR UPDATE",
            """
                SELECT c.id
                FROM collab_photo_comments c
                JOIN collab_sessions s ON s.id = c.collab_session_id
                WHERE s.gallery_id = :galleryId
                ORDER BY c.id
                FOR UPDATE OF c
            """.trimIndent(),
            """
                SELECT l.id
                FROM collab_photo_likes l
                JOIN collab_sessions s ON s.id = l.collab_session_id
                WHERE s.gallery_id = :galleryId
                ORDER BY l.id
                FOR UPDATE OF l
            """.trimIndent(),
            "SELECT id FROM retouch_rounds WHERE gallery_id = :galleryId ORDER BY id FOR UPDATE",
            "SELECT id FROM retouch_photos WHERE gallery_id = :galleryId ORDER BY id FOR UPDATE",
        ).forEach { sql ->
            jdbcClient.sql(sql)
                .param("galleryId", galleryId)
                .query { rs, _ -> rs.getLong("id") }
                .list()
        }
        return true
    }

    /** 잠금 뒤 재검사한다. 관리자 batch/child trash가 하나라도 있으면 제품 purge가 양보한다. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    fun isGalleryProtectedByAdmin(galleryId: Long): Boolean = jdbcClient.sql(
        "SELECT NOT (${notProtectedByAdminSql(":galleryId")})",
    )
        .param("galleryId", galleryId)
        .query { rs, _ -> rs.getBoolean(1) }
        .single()

    /**
     * S3 삭제가 성공한 뒤 갤러리와 다형 관리자 payload를 한 트랜잭션에서 걷는다.
     * 다른 갤러리의 같은 숫자 id는 resource type까지 함께 비교하므로 대상이 되지 않는다.
     */
    @Transactional
    fun deleteGallery(galleryId: Long): Int {
        deleteOperationalPayloadsForGallery(galleryId)
        return jdbcClient.sql("DELETE FROM galleries WHERE id = :galleryId")
            .param("galleryId", galleryId)
            .update()
    }

    fun findExpiredGalleryIds(cutoff: ZonedDateTime): List<Long> =
        jdbcClient.sql(
            """
            SELECT g.id
            FROM galleries g
            WHERE g.deleted_at IS NOT NULL
              AND g.deleted_at < :cutoff
              AND ${notProtectedByAdminSql("g.id")}
            ORDER BY g.id
            """.trimIndent(),
        )
            .param("cutoff", cutoff.toOffsetDateTime())
            .query { rs, _ -> rs.getLong("id") }
            .list()

    // --- 사진 ---

    @Transactional(propagation = Propagation.MANDATORY)
    fun lockPurgeCoordinationForPhotos(photoIds: Collection<Long>) {
        photoIds.toSet().sorted().forEach { photoId -> lockPurgeCoordination("PHOTO", photoId) }
    }

    fun findTrashedPhotos(galleryId: Long): List<TrashedPhotoRow> =
        jdbcClient.sql(
            """
            SELECT id, original_file_name, status, storage_key, preview_key, deleted_at
            FROM photos
            WHERE gallery_id = :galleryId
              AND deleted_at IS NOT NULL
              AND ${notProtectedByAdminForPhotoSql("photos.id")}
              AND ${notClaimedForPhotoSql("photos.id", "photos.gallery_id")}
            ORDER BY deleted_at DESC, id DESC
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .query { rs, _ ->
                TrashedPhotoRow(
                    photoId = rs.getLong("id"),
                    originalFileName = rs.getString("original_file_name"),
                    status = PhotoStatus.valueOf(rs.getString("status")),
                    storageKey = rs.getString("storage_key"),
                    previewKey = rs.getString("preview_key"),
                    deletedAt = zonedDateTimeOf(rs, "deleted_at"),
                )
            }
            .list()

    /**
     * 즉시 물리 삭제 대상. 갤러리와 휴지통 여부로 좁히므로, 돌아온 수가 요청한 수보다
     * 적다는 것이 곧 "휴지통에 없는 id가 섞였다"는 뜻이다.
     */
    fun findTrashedPhotoTargets(galleryId: Long, photoIds: Collection<Long>): List<TrashedPhotoTarget> =
        jdbcClient.sql(
            """
            SELECT id, storage_key, preview_key, upload_url_expires_at
            FROM photos
            WHERE gallery_id = :galleryId
              AND id IN (:photoIds)
              AND deleted_at IS NOT NULL
              AND ${notProtectedByAdminForPhotoSql("photos.id")}
              AND NOT EXISTS (
                  SELECT 1 FROM product_purge_claims claim
                  WHERE claim.resource_type = 'GALLERY' AND claim.resource_id = photos.gallery_id
              )
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("photoIds", photoIds)
            .query { rs, _ -> photoTarget(rs) }
            .list()

    /** 갤러리 물리 삭제용 — 휴지통에 있든 살아 있든 이 갤러리의 모든 사진. */
    fun findAllPhotoTargets(galleryId: Long): List<TrashedPhotoTarget> =
        jdbcClient.sql(
            "SELECT id, storage_key, preview_key, upload_url_expires_at FROM photos WHERE gallery_id = :galleryId",
        )
            .param("galleryId", galleryId)
            .query { rs, _ -> photoTarget(rs) }
            .list()

    fun findExpiredPhotoTargets(cutoff: ZonedDateTime): List<TrashedPhotoTarget> =
        jdbcClient.sql(
            """
            SELECT id, storage_key, preview_key, upload_url_expires_at
            FROM photos
            WHERE photos.deleted_at IS NOT NULL
              AND photos.deleted_at < :cutoff
              AND ${notProtectedByAdminForPhotoSql("photos.id")}
            ORDER BY photos.id
            """.trimIndent(),
        )
            .param("cutoff", cutoff.toOffsetDateTime())
            .query { rs, _ -> photoTarget(rs) }
            .list()

    fun restorePhotos(galleryId: Long, photoIds: Collection<Long>): Int =
        jdbcClient.sql(
            """
            UPDATE photos
            SET deleted_at = NULL,
                version = version + 1
            WHERE gallery_id = :galleryId
              AND id IN (:photoIds)
              AND deleted_at IS NOT NULL
              AND ${notProtectedByAdminForPhotoSql("photos.id")}
              AND ${notClaimedForPhotoSql("photos.id", "photos.gallery_id")}
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("photoIds", photoIds)
            .update()

    /**
     * 사진 purge가 건드릴 관리자 parent/child와 제품 사진을 관리자 cascade 순서로 잠근다.
     * 반환된 사진 수가 요청 수와 다르면 호출자가 claim 트랜잭션 전체를 취소해야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockPhotoPurgeScope(photoIds: Collection<Long>): List<TrashedPhotoTarget> {
        if (photoIds.isEmpty()) return emptyList()
        val targets = jdbcClient.sql(
            """
            SELECT id, storage_key, preview_key, upload_url_expires_at
            FROM photos
            WHERE id IN (:photoIds) AND deleted_at IS NOT NULL
            ORDER BY id
            FOR UPDATE
            """.trimIndent(),
        )
            .param("photoIds", photoIds)
            .query { rs, _ -> photoTarget(rs) }
            .list()

        val scopeLocks = listOf(
            """
                SELECT s.id
                FROM photo_selections s
                WHERE EXISTS (
                    SELECT 1 FROM photo_selection_items i
                    WHERE i.selection_id = s.id AND i.photo_id IN (:photoIds)
                )
                ORDER BY s.id FOR UPDATE OF s
            """.trimIndent(),
            "SELECT id FROM photo_selection_items WHERE photo_id IN (:photoIds) ORDER BY id FOR UPDATE",
            """
                SELECT s.id
                FROM collab_sessions s
                WHERE EXISTS (
                    SELECT 1 FROM collab_photo_comments c
                    WHERE c.collab_session_id = s.id AND c.photo_id IN (:photoIds)
                ) OR EXISTS (
                    SELECT 1 FROM collab_photo_likes l
                    WHERE l.collab_session_id = s.id AND l.photo_id IN (:photoIds)
                ) OR EXISTS (
                    SELECT 1
                    FROM detail_folders d
                    JOIN photo_category_assignments a ON a.detail_folder_id = d.id
                    WHERE d.concept_folder_id = s.concept_folder_id
                      AND a.photo_id IN (:photoIds)
                ) OR (s.concept_folder_id IS NULL AND EXISTS (
                    SELECT 1 FROM collab_session_photos membership
                    WHERE membership.collab_session_id = s.id AND membership.photo_id IN (:photoIds)
                ))
                ORDER BY s.id FOR UPDATE OF s
            """.trimIndent(),
            "SELECT id FROM collab_session_photos WHERE photo_id IN (:photoIds) ORDER BY id FOR UPDATE",
            """
                SELECT c.id
                FROM collab_photo_comments c
                WHERE c.photo_id IN (:photoIds)
                ORDER BY c.id FOR UPDATE OF c
            """.trimIndent(),
            """
                SELECT l.id
                FROM collab_photo_likes l
                WHERE l.photo_id IN (:photoIds)
                ORDER BY l.id FOR UPDATE OF l
            """.trimIndent(),
            """
                SELECT r.id
                FROM retouch_rounds r
                WHERE EXISTS (
                    SELECT 1 FROM retouch_photos p
                    WHERE p.round_id = r.id AND p.photo_id IN (:photoIds)
                )
                ORDER BY r.id FOR UPDATE OF r
            """.trimIndent(),
            "SELECT id FROM retouch_photos WHERE photo_id IN (:photoIds) ORDER BY id FOR UPDATE",
        )
        scopeLocks.forEach { sql ->
            jdbcClient.sql(sql)
                .param("photoIds", photoIds)
                .query { rs, _ -> rs.getLong("id") }
                .list()
        }
        return targets
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    fun protectedPhotoIds(photoIds: Collection<Long>): Set<Long> {
        if (photoIds.isEmpty()) return emptySet()
        return jdbcClient.sql(
            """
            SELECT p.id
            FROM photos p
            WHERE p.id IN (:photoIds)
              AND NOT (${notProtectedByAdminForPhotoSql("p.id")})
            ORDER BY p.id
            """.trimIndent(),
        )
            .param("photoIds", photoIds)
            .query { rs, _ -> rs.getLong("id") }
            .list()
            .toSet()
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    fun hasPhotoClaimInGallery(galleryId: Long): Boolean = jdbcClient.sql(
        """
        SELECT EXISTS (
            SELECT 1
            FROM product_purge_claims claim
            JOIN photos p ON p.id = claim.resource_id
            WHERE claim.resource_type = 'PHOTO' AND p.gallery_id = :galleryId
        )
        """.trimIndent(),
    )
        .param("galleryId", galleryId)
        .query { rs, _ -> rs.getBoolean(1) }
        .single()

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    fun hasGalleryClaimForPhotos(photoIds: Collection<Long>): Boolean {
        if (photoIds.isEmpty()) return false
        return jdbcClient.sql(
            """
            SELECT EXISTS (
                SELECT 1
                FROM photos p
                JOIN product_purge_claims claim
                  ON claim.resource_type = 'GALLERY' AND claim.resource_id = p.gallery_id
                WHERE p.id IN (:photoIds)
            )
            """.trimIndent(),
        )
            .param("photoIds", photoIds)
            .query { rs, _ -> rs.getBoolean(1) }
            .single()
    }

    /** 만료 lease만 takeover한다. 관리자 writer는 lease 만료 여부와 무관하게 claim 존재를 거절한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun tryAcquirePurgeClaim(
        resourceType: String,
        resourceId: Long,
        token: UUID,
        now: ZonedDateTime,
        leaseUntil: ZonedDateTime,
    ): Boolean = jdbcClient.sql(
        """
        INSERT INTO product_purge_claims (resource_type, resource_id, claim_token, claimed_at, lease_until)
        VALUES (:resourceType, :resourceId, :token, :now, :leaseUntil)
        ON CONFLICT (resource_type, resource_id) DO UPDATE
        SET claim_token = EXCLUDED.claim_token,
            claimed_at = EXCLUDED.claimed_at,
            lease_until = EXCLUDED.lease_until
        WHERE product_purge_claims.lease_until <= :now
        RETURNING resource_id
        """.trimIndent(),
    )
        .param("resourceType", resourceType)
        .param("resourceId", resourceId)
        .param("token", token)
        .param("now", now.toOffsetDateTime())
        .param("leaseUntil", leaseUntil.toOffsetDateTime())
        .query { rs, _ -> rs.getLong("resource_id") }
        .optional()
        .isPresent

    @Transactional(propagation = Propagation.MANDATORY)
    fun ownsPurgeClaimsForUpdate(resourceType: String, resourceIds: Collection<Long>, token: UUID): Boolean {
        if (resourceIds.isEmpty()) return false
        val owned = jdbcClient.sql(
            """
            SELECT resource_id
            FROM product_purge_claims
            WHERE resource_type = :resourceType
              AND resource_id IN (:resourceIds)
              AND claim_token = :token
            ORDER BY resource_id
            FOR UPDATE
            """.trimIndent(),
        )
            .param("resourceType", resourceType)
            .param("resourceIds", resourceIds)
            .param("token", token)
            .query { rs, _ -> rs.getLong("resource_id") }
            .list()
        return owned.toSet() == resourceIds.toSet()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteOwnedPurgeClaims(resourceType: String, resourceIds: Collection<Long>, token: UUID): Int {
        if (resourceIds.isEmpty()) return 0
        return jdbcClient.sql(
            """
            DELETE FROM product_purge_claims
            WHERE resource_type = :resourceType
              AND resource_id IN (:resourceIds)
              AND claim_token = :token
            """.trimIndent(),
        )
            .param("resourceType", resourceType)
            .param("resourceIds", resourceIds)
            .param("token", token)
            .update()
    }

    /** S3 삭제가 성공한 사진 id에 정확히 결속된 관리자 payload와 사진 행을 함께 걷는다. */
    @Transactional
    fun deletePhotos(photoIds: Collection<Long>): Int {
        deleteOperationalPayloadsForPhotos(photoIds)
        return jdbcClient.sql("DELETE FROM photos WHERE id IN (:photoIds)")
            .param("photoIds", photoIds)
            .update()
    }

    // --- 물리 삭제 S3 객체 ---

    /**
     * 갤러리와 함께 사라질 현재 사진·리비전·교체 업로드·보정 객체 중 다른 행이 공유하지
     * 않는 key만 반환한다. 공유 key는 무관한 데이터 보존을 우선해 삭제하지 않는다.
     */
    fun findPurgeObjectKeys(galleryId: Long): List<String> = jdbcClient.sql(
        purgeObjectKeysSql(
            photoPredicate = "p.gallery_id = :galleryId",
            retouchPhotoPredicate = "rp.gallery_id = :galleryId",
        ),
    )
            .param("galleryId", galleryId)
            .query { rs, _ -> rs.getString("storage_key") }
            .list()

    /** 사진 id 집합에 속하면서 다른 사진·보정 행이 공유하지 않는 S3 key만 반환한다. */
    fun findPurgeObjectKeysByPhotoIds(photoIds: Collection<Long>): List<String> = jdbcClient.sql(
        purgeObjectKeysSql(
            photoPredicate = "p.id IN (:photoIds)",
            retouchPhotoPredicate = "rp.photo_id IN (:photoIds)",
        ),
    )
            .param("photoIds", photoIds)
            .query { rs, _ -> rs.getString("storage_key") }
            .list()

    private fun deleteOperationalPayloadsForGallery(galleryId: Long): Int = jdbcClient.sql(
        """
        WITH targets(resource_type, resource_id) AS MATERIALIZED (
            SELECT 'GALLERY', g.id FROM galleries g WHERE g.id = :galleryId
            UNION ALL
            SELECT 'PHOTO', p.id FROM photos p WHERE p.gallery_id = :galleryId
            UNION ALL
            SELECT 'SELECTION', s.id FROM photo_selections s WHERE s.gallery_id = :galleryId
            UNION ALL
            SELECT 'COLLABORATION', c.id FROM collab_sessions c WHERE c.gallery_id = :galleryId
            UNION ALL
            SELECT 'RETOUCH_REQUEST', r.id FROM retouch_rounds r WHERE r.gallery_id = :galleryId
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
        ), expired_revision_payloads AS (
            UPDATE admin_entity_revisions r
            SET before_restore_payload = NULL,
                after_restore_payload = NULL,
                restore_expires_at = LEAST(r.restore_expires_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            FROM targets t
            WHERE r.target_type = t.resource_type
              AND r.target_id = t.resource_id::TEXT
              AND (r.before_restore_payload IS NOT NULL OR r.after_restore_payload IS NOT NULL)
            RETURNING 1
        )
        SELECT
            (SELECT COUNT(*) FROM deleted_idempotency)
            + (SELECT COUNT(*) FROM deleted_processing_jobs)
            + (SELECT COUNT(*) FROM deleted_notifications)
            + (SELECT COUNT(*) FROM deleted_inbox_notifications)
            + (SELECT COUNT(*) FROM expired_revision_payloads)
        """.trimIndent(),
    )
        .param("galleryId", galleryId)
        .query { rs, _ -> rs.getInt(1) }
        .single()

    private fun deleteOperationalPayloadsForPhotos(photoIds: Collection<Long>): Int = jdbcClient.sql(
        """
        WITH targets(resource_type, resource_id) AS MATERIALIZED (
            SELECT 'PHOTO', p.id
            FROM photos p
            WHERE p.id IN (:photoIds)
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
        ), expired_revision_payloads AS (
            UPDATE admin_entity_revisions r
            SET before_restore_payload = NULL,
                after_restore_payload = NULL,
                restore_expires_at = LEAST(r.restore_expires_at, CURRENT_TIMESTAMP),
                updated_at = CURRENT_TIMESTAMP
            FROM targets t
            WHERE r.target_type = t.resource_type
              AND r.target_id = t.resource_id::TEXT
              AND (r.before_restore_payload IS NOT NULL OR r.after_restore_payload IS NOT NULL)
            RETURNING 1
        )
        SELECT
            (SELECT COUNT(*) FROM deleted_idempotency)
            + (SELECT COUNT(*) FROM deleted_processing_jobs)
            + (SELECT COUNT(*) FROM deleted_notifications)
            + (SELECT COUNT(*) FROM deleted_inbox_notifications)
            + (SELECT COUNT(*) FROM expired_revision_payloads)
        """.trimIndent(),
    )
        .param("photoIds", photoIds)
        .query { rs, _ -> rs.getInt(1) }
        .single()

    private fun notProtectedByAdminSql(galleryIdExpression: String): String =
        """
        NOT EXISTS (
            SELECT 1
            FROM admin_trash_entries e
            JOIN admin_trash_batches b ON b.id = e.batch_id
            WHERE b.status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
              AND (
                  (e.resource_type = 'GALLERY' AND e.resource_id = $galleryIdExpression)
                  OR (
                      e.resource_type = 'GALLERY_MEMBER'
                      AND EXISTS (
                          SELECT 1 FROM gallery_members gm
                          WHERE gm.id = e.resource_id AND gm.gallery_id = $galleryIdExpression
                      )
                  )
                  OR (
                      e.resource_type = 'PHOTO'
                      AND EXISTS (
                          SELECT 1 FROM photos p
                          WHERE p.id = e.resource_id AND p.gallery_id = $galleryIdExpression
                      )
                  )
                  OR (
                      e.resource_type = 'SELECTION'
                      AND EXISTS (
                          SELECT 1 FROM photo_selections s
                          WHERE s.id = e.resource_id AND s.gallery_id = $galleryIdExpression
                      )
                  )
                  OR (
                      e.resource_type = 'COLLABORATION'
                      AND EXISTS (
                          SELECT 1 FROM collab_sessions c
                          WHERE c.id = e.resource_id AND c.gallery_id = $galleryIdExpression
                      )
                  )
                  OR (
                      e.resource_type = 'COLLAB_COMMENT'
                      AND EXISTS (
                          SELECT 1
                          FROM collab_photo_comments c
                          JOIN collab_sessions s ON s.id = c.collab_session_id
                          WHERE c.id = e.resource_id AND s.gallery_id = $galleryIdExpression
                      )
                  )
                  OR (
                      e.resource_type = 'RETOUCH_REQUEST'
                      AND EXISTS (
                          SELECT 1 FROM retouch_rounds r
                          WHERE r.id = e.resource_id AND r.gallery_id = $galleryIdExpression
                      )
                  )
              )
        )
        AND NOT EXISTS (
            SELECT 1
            FROM admin_trash_batches root_batch
            WHERE root_batch.status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
              AND (
                  (root_batch.root_type = 'GALLERY' AND root_batch.root_id = $galleryIdExpression)
                  OR (
                      root_batch.root_type = 'STUDIO'
                      AND EXISTS (
                          SELECT 1 FROM galleries protected_gallery
                          WHERE protected_gallery.id = $galleryIdExpression
                            AND protected_gallery.workspace_id = root_batch.root_id
                      )
                  )
                  OR (
                      root_batch.root_type = 'USER'
                      AND EXISTS (
                          SELECT 1
                          FROM galleries protected_gallery
                          JOIN workspace_members protected_member
                            ON protected_member.workspace_id = protected_gallery.workspace_id
                           AND protected_member.deleted_at IS NULL
                          WHERE protected_gallery.id = $galleryIdExpression
                            AND protected_member.user_id = root_batch.root_id
                      )
                  )
              )
        )
        AND NOT EXISTS (
            SELECT 1
            FROM admin_child_trash_records child
            WHERE child.status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
              AND (
                  (
                      child.resource_type IN ('COLLAB_COMMENT', 'COLLAB_LIKE')
                      AND EXISTS (
                          SELECT 1 FROM collab_sessions session
                          WHERE session.id = child.parent_id
                            AND session.gallery_id = $galleryIdExpression
                      )
                  )
                  OR (
                      child.resource_type = 'RETOUCH_ITEM'
                      AND EXISTS (
                          SELECT 1 FROM retouch_rounds round
                          WHERE round.id = child.parent_id
                            AND round.gallery_id = $galleryIdExpression
                      )
                  )
              )
        )
        """.trimIndent()

    private fun notProtectedByAdminForPhotoSql(photoIdExpression: String): String =
        """
        NOT EXISTS (
            SELECT 1
            FROM admin_trash_entries e
            JOIN admin_trash_batches b ON b.id = e.batch_id
            WHERE b.status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
              AND (
                  (e.resource_type = 'PHOTO' AND e.resource_id = $photoIdExpression)
                  OR (
                      e.resource_type = 'SELECTION'
                      AND EXISTS (
                          SELECT 1 FROM photo_selection_items item
                          WHERE item.selection_id = e.resource_id AND item.photo_id = $photoIdExpression
                      )
                  )
                  OR (
                      e.resource_type = 'COLLABORATION'
                      AND EXISTS (
                          SELECT 1
                          FROM collab_sessions session
                          WHERE session.id = e.resource_id AND (
                              EXISTS (
                                  SELECT 1 FROM detail_folders detail
                                  JOIN photo_category_assignments assignment ON assignment.detail_folder_id = detail.id
                                  WHERE detail.concept_folder_id = session.concept_folder_id
                                    AND assignment.photo_id = $photoIdExpression
                              ) OR (session.concept_folder_id IS NULL AND EXISTS (
                                  SELECT 1 FROM collab_session_photos membership
                                  WHERE membership.collab_session_id = session.id
                                    AND membership.photo_id = $photoIdExpression
                              ))
                          )
                      )
                  )
                  OR (
                      e.resource_type = 'COLLAB_COMMENT'
                      AND EXISTS (
                          SELECT 1
                          FROM collab_photo_comments comment
                          WHERE comment.id = e.resource_id AND comment.photo_id = $photoIdExpression
                      )
                  )
                  OR (
                      e.resource_type = 'RETOUCH_REQUEST'
                      AND EXISTS (
                          SELECT 1 FROM retouch_photos item
                          WHERE item.round_id = e.resource_id AND item.photo_id = $photoIdExpression
                      )
                  )
              )
        )
        AND NOT EXISTS (
            SELECT 1
            FROM admin_trash_batches root_batch
            WHERE root_batch.status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
              AND EXISTS (
                  SELECT 1
                  FROM photos protected_photo
                  JOIN galleries protected_gallery ON protected_gallery.id = protected_photo.gallery_id
                  WHERE protected_photo.id = $photoIdExpression
                    AND (
                        (root_batch.root_type = 'GALLERY' AND root_batch.root_id = protected_gallery.id)
                        OR (root_batch.root_type = 'STUDIO' AND root_batch.root_id = protected_gallery.workspace_id)
                        OR (root_batch.root_type = 'USER' AND EXISTS (
                            SELECT 1 FROM workspace_members protected_member
                            WHERE protected_member.workspace_id = protected_gallery.workspace_id
                              AND protected_member.user_id = root_batch.root_id
                              AND protected_member.deleted_at IS NULL
                        ))
                    )
              )
        )
        AND NOT EXISTS (
            SELECT 1
            FROM admin_child_trash_records child
            WHERE child.status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED')
              AND (
                  (
                      child.resource_type = 'COLLAB_COMMENT'
                      AND EXISTS (
                          SELECT 1
                          FROM collab_photo_comments comment
                          WHERE comment.id = child.resource_id AND comment.photo_id = $photoIdExpression
                      )
                  )
                  OR (
                      child.resource_type = 'COLLAB_LIKE'
                      AND EXISTS (
                          SELECT 1
                          FROM collab_photo_likes reaction
                          WHERE reaction.id = child.resource_id AND reaction.photo_id = $photoIdExpression
                      )
                  )
                  OR (
                      child.resource_type = 'RETOUCH_ITEM'
                      AND EXISTS (
                          SELECT 1 FROM retouch_photos item
                          WHERE item.id = child.resource_id AND item.photo_id = $photoIdExpression
                      )
                  )
              )
        )
        """.trimIndent()

    private fun notClaimedForGallerySql(galleryIdExpression: String): String =
        """
        NOT EXISTS (
            SELECT 1 FROM product_purge_claims claim
            WHERE claim.resource_type = 'GALLERY' AND claim.resource_id = $galleryIdExpression
        )
        AND NOT EXISTS (
            SELECT 1
            FROM product_purge_claims claim
            JOIN photos p ON p.id = claim.resource_id
            WHERE claim.resource_type = 'PHOTO' AND p.gallery_id = $galleryIdExpression
        )
        """.trimIndent()

    private fun notClaimedForPhotoSql(photoIdExpression: String, galleryIdExpression: String): String =
        """
        NOT EXISTS (
            SELECT 1 FROM product_purge_claims claim
            WHERE claim.resource_type = 'PHOTO' AND claim.resource_id = $photoIdExpression
        )
        AND NOT EXISTS (
            SELECT 1 FROM product_purge_claims claim
            WHERE claim.resource_type = 'GALLERY' AND claim.resource_id = $galleryIdExpression
        )
        """.trimIndent()

    private fun lockIds(sql: String, galleryId: Long) {
        jdbcClient.sql(sql)
            .param("galleryId", galleryId)
            .query { rs, _ -> rs.getLong("id") }
            .list()
    }

    private fun lockPurgeCoordination(resourceType: String, resourceId: Long) {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:coordinationKey, 0))")
            .param("coordinationKey", "WES_PRODUCT_PURGE:$resourceType:$resourceId")
            .query { _, _ -> Unit }
            .single()
    }

    private fun photoTarget(rs: ResultSet): TrashedPhotoTarget =
        TrashedPhotoTarget(
            photoId = rs.getLong("id"),
            storageKey = rs.getString("storage_key"),
            previewKey = rs.getString("preview_key"),
            uploadUrlExpiresAt = rs.getObject("upload_url_expires_at", OffsetDateTime::class.java)?.toInstant(),
        )

    private fun zonedDateTimeOf(rs: ResultSet, column: String): ZonedDateTime =
        rs.getObject(column, OffsetDateTime::class.java).toZonedDateTime()
}
