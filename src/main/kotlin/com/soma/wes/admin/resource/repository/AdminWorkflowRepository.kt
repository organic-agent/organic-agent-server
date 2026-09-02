package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.support.AdminAlbumTemplateLayout
import com.soma.wes.admin.resource.support.InvalidAlbumTemplateLayoutException
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.global.filter.HttpLoggingFilter
import org.slf4j.MDC
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.ZonedDateTime

@Repository
class AdminWorkflowRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val resourceRepository: AdminResourceRepository,
) {

    fun reserveWorkflow(
        action: String,
        idempotencyKey: String,
        requestHash: String,
        targetType: AdminResourceType,
        targetId: Long,
    ): WorkflowReservation {
        val correlationId = MDC.get(HttpLoggingFilter.TRACE_ID_KEY)
            ?.takeIf { TRACE_ID.matches(it) }
            ?: "untracked"
        val inserted = jdbcClient.sql(
            """
            INSERT INTO admin_idempotency_keys
                (action, idempotency_key, request_hash, status, target_type, target_id,
                 correlation_id, attempt_count, created_at, updated_at)
            VALUES
                (:action, :idempotencyKey, :requestHash, 'PENDING', :targetType, :targetId,
                 :correlationId, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (action, idempotency_key) DO NOTHING
            """.trimIndent(),
        )
            .param("action", action)
            .param("idempotencyKey", idempotencyKey)
            .param("requestHash", requestHash)
            .param("targetType", targetType.name)
            .param("targetId", targetId.toString())
            .param("correlationId", correlationId)
            .update()
        if (inserted == 1) return WorkflowReservation(null, null, null)

        return jdbcClient.sql(
            """
            SELECT request_hash, status, result_payload
            FROM admin_idempotency_keys
            WHERE action = :action AND idempotency_key = :idempotencyKey
            """.trimIndent(),
        )
            .param("action", action)
            .param("idempotencyKey", idempotencyKey)
            .query { rs, _ -> WorkflowReservation(
                requestHash = rs.getString("request_hash"),
                status = rs.getString("status"),
                resultPayload = rs.getString("result_payload"),
            ) }
            .single()
    }

    fun completeWorkflow(action: String, idempotencyKey: String, resultPayload: String) {
        val updated = jdbcClient.sql(
            """
            UPDATE admin_idempotency_keys
            SET status = 'COMPLETED', result_payload = :resultPayload, updated_at = CURRENT_TIMESTAMP
            WHERE action = :action AND idempotency_key = :idempotencyKey AND status = 'PENDING'
            """.trimIndent(),
        )
            .param("resultPayload", resultPayload)
            .param("action", action)
            .param("idempotencyKey", idempotencyKey)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.REPROCESS_ALREADY_REQUESTED)
    }

    fun failWorkflow(action: String, idempotencyKey: String, failureCode: String) {
        jdbcClient.sql(
            """
            UPDATE admin_idempotency_keys
            SET status = 'FAILED', failure_code = :failureCode, updated_at = CURRENT_TIMESTAMP
            WHERE action = :action AND idempotency_key = :idempotencyKey AND status = 'PENDING'
            """.trimIndent(),
        )
            .param("failureCode", failureCode.take(80))
            .param("action", action)
            .param("idempotencyKey", idempotencyKey)
            .update()
    }

    fun terminateUserSessions(userId: Long, expectedVersion: Long): Int {
        requireVersion("users", userId, expectedVersion)
        val deleted = jdbcClient.sql("DELETE FROM refresh_tokens WHERE user_id = :userId")
            .param("userId", userId)
            .update()
        bumpVersion("users", userId, expectedVersion)
        return deleted
    }

    fun setStudioOwner(studioId: Long, userId: Long, expectedVersion: Long): StudioOwnerResult {
        requireVersion("studios", studioId, expectedVersion, lock = true)
        val previousOwnerId = jdbcClient.sql(
            """
            SELECT user_id FROM workspace_members
            WHERE workspace_id = :studioId AND role = 'OWNER' AND deleted_at IS NULL
            ORDER BY id LIMIT 1
            """.trimIndent(),
        )
            .param("studioId", studioId)
            .query { rs, _ -> rs.getLong(1) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
        val alreadyOwner = jdbcClient.sql(
            "SELECT COUNT(*) FROM workspace_members WHERE workspace_id = :studioId AND user_id = :userId AND role = 'OWNER' AND deleted_at IS NULL",
        ).param("studioId", studioId).param("userId", userId)
            .query { rs, _ -> rs.getLong(1) }.single() > 0
        if (alreadyOwner) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)

        jdbcClient.sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role, version, created_at, updated_at)
            VALUES (:studioId, :userId, 'OWNER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (workspace_id, user_id)
            DO UPDATE SET role = 'OWNER', deleted_at = NULL,
                          version = workspace_members.version + 1, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("studioId", studioId).param("userId", userId).update()
        bumpVersion("studios", studioId, expectedVersion)
        return StudioOwnerResult(previousOwnerId, userId)
    }

    fun addStudioMember(studioId: Long, userId: Long, expectedVersion: Long): StudioMemberResult {
        requireVersion("studios", studioId, expectedVersion, lock = true)
        val activeMembership = jdbcClient.sql(
            "SELECT COUNT(*) FROM workspace_members WHERE workspace_id = :studioId AND user_id = :userId AND deleted_at IS NULL",
        ).param("studioId", studioId).param("userId", userId)
            .query { rs, _ -> rs.getLong(1) }.single()
        if (activeMembership != 0L) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        val memberId = jdbcClient.sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role, version, created_at, updated_at)
            VALUES (:studioId, :userId, 'MEMBER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (workspace_id, user_id)
            DO UPDATE SET role = 'MEMBER', deleted_at = NULL,
                          version = workspace_members.version + 1, updated_at = CURRENT_TIMESTAMP
            RETURNING id
            """.trimIndent(),
        ).param("studioId", studioId).param("userId", userId)
            .query { rs, _ -> rs.getLong(1) }.single()
        bumpVersion("studios", studioId, expectedVersion)
        return StudioMemberResult(memberId)
    }

    fun removeStudioMember(studioId: Long, memberId: Long, expectedVersion: Long) {
        requireVersion("studios", studioId, expectedVersion, lock = true)
        val updated = jdbcClient.sql(
            """
            UPDATE workspace_members m
            SET deleted_at = CURRENT_TIMESTAMP, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE m.id = :memberId AND m.workspace_id = :studioId AND m.role = 'MEMBER' AND m.deleted_at IS NULL
            """.trimIndent(),
        ).param("memberId", memberId).param("studioId", studioId).update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        bumpVersion("studios", studioId, expectedVersion)
    }

    fun addGalleryMember(galleryId: Long, userId: Long, expectedVersion: Long): GalleryMemberResult {
        val gallery = lockGalleryScope(galleryId, expectedVersion)
        val activeStudioMembership = jdbcClient.sql(
            """
            SELECT COUNT(*) FROM workspace_members
            WHERE workspace_id = :studioId AND user_id = :userId AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("studioId", gallery.studioId)
            .param("userId", userId)
            .query { rs, _ -> rs.getLong(1) }
            .single()
        if (activeStudioMembership != 0L) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)

        val existing = jdbcClient.sql(
            """
            SELECT id, deleted_at FROM gallery_members
            WHERE gallery_id = :galleryId AND user_id = :userId
            FOR UPDATE
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("userId", userId)
            .query { rs, _ -> rs.getLong("id") to rs.getObject("deleted_at") }
            .optional()
            .orElse(null)
        if (existing?.second == null && existing != null) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val activeMemberCount = jdbcClient.sql(
            "SELECT COUNT(*) FROM gallery_members WHERE gallery_id = :galleryId AND deleted_at IS NULL",
        )
            .param("galleryId", galleryId)
            .query { rs, _ -> rs.getLong(1) }
            .single()
        if (activeMemberCount >= GalleryMember.MAX_PER_GALLERY) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        // 제품 초대와 같은 정책이다. 다른 스튜디오의 사진작가도 본인 결혼식 갤러리에는
        // 들어올 수 있으므로 기존 PHOTOGRAPHER 타입을 CLIENT로 바꾸거나 거절하지 않는다.

        val memberId = if (existing == null) {
            jdbcClient.sql(
                """
                INSERT INTO gallery_members (gallery_id, user_id, version, created_at, updated_at)
                VALUES (:galleryId, :userId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """.trimIndent(),
            )
                .param("galleryId", galleryId)
                .param("userId", userId)
                .query { rs, _ -> rs.getLong("id") }
                .single()
        } else {
            val restored = jdbcClient.sql(
                """
                UPDATE gallery_members
                SET deleted_at = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :memberId AND deleted_at IS NOT NULL
                """.trimIndent(),
            ).param("memberId", existing.first).update()
            if (restored != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
            existing.first
        }
        bumpVersion("galleries", galleryId, expectedVersion)
        return GalleryMemberResult(memberId)
    }

    fun removeGalleryMember(galleryId: Long, memberId: Long, expectedVersion: Long): Int {
        requireVersion("galleries", galleryId, expectedVersion, lock = true)
        val updated = jdbcClient.sql(
            """
            UPDATE gallery_members
            SET deleted_at = CURRENT_TIMESTAMP, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :memberId AND gallery_id = :galleryId AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("memberId", memberId)
            .param("galleryId", galleryId)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        bumpVersion("galleries", galleryId, expectedVersion)
        return updated
    }

    fun updateGalleryStates(
        galleryId: Long,
        publicStatus: String,
        workflowStatus: String,
        selectionDeadline: OffsetDateTime?,
        expectedVersion: Long,
    ) {
        if (publicStatus !in PUBLIC_STATUSES || workflowStatus !in WORKFLOW_STATUSES) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val updated = jdbcClient.sql(
            """
            UPDATE galleries
            SET status = :publicStatus, workflow_status = :workflowStatus,
                selection_deadline = :selectionDeadline,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :galleryId AND version = :expectedVersion AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("publicStatus", publicStatus)
            .param("workflowStatus", workflowStatus)
            .param("selectionDeadline", selectionDeadline)
            .param("galleryId", galleryId)
            .param("expectedVersion", expectedVersion)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    fun transitionGallery(galleryId: Long, transition: GalleryTransition, expectedVersion: Long) {
        if (transition == GalleryTransition.COMPLETE) {
            val submitted = jdbcClient.sql(
                "SELECT COUNT(*) FROM photo_selections WHERE gallery_id = :galleryId AND status = 'SUBMITTED'",
            ).param("galleryId", galleryId).query { rs, _ -> rs.getLong(1) }.single()
            if (submitted != 1L) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val updated = jdbcClient.sql(
            """
            UPDATE galleries
            SET status = :publicStatus, workflow_status = :workflowStatus,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :galleryId AND version = :expectedVersion AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("publicStatus", transition.publicStatus)
            .param("workflowStatus", transition.workflowStatus)
            .param("galleryId", galleryId)
            .param("expectedVersion", expectedVersion)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    fun reissueGalleryInvite(
        galleryId: Long,
        token: String,
        expiresAt: ZonedDateTime,
        expectedVersion: Long,
    ): Long {
        requireVersion("galleries", galleryId, expectedVersion)
        jdbcClient.sql(
            """
            UPDATE gallery_invites
            SET revoked_at = CURRENT_TIMESTAMP, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE gallery_id = :galleryId AND revoked_at IS NULL
            """.trimIndent(),
        ).param("galleryId", galleryId).update()
        val inviteId = jdbcClient.sql(
            """
            INSERT INTO gallery_invites (gallery_id, token, expires_at, version, created_at, updated_at)
            VALUES (:galleryId, :token, :expiresAt, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("token", token)
            .param("expiresAt", expiresAt.toOffsetDateTime())
            .query { rs, _ -> rs.getLong("id") }
            .single()
        bumpVersion("galleries", galleryId, expectedVersion)
        return inviteId
    }

    fun revokeGalleryInvite(galleryId: Long, inviteId: Long, expectedVersion: Long) {
        requireVersion("galleries", galleryId, expectedVersion)
        val updated = jdbcClient.sql(
            """
            UPDATE gallery_invites
            SET revoked_at = CURRENT_TIMESTAMP, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :inviteId AND gallery_id = :galleryId AND revoked_at IS NULL
            """.trimIndent(),
        )
            .param("inviteId", inviteId)
            .param("galleryId", galleryId)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        bumpVersion("galleries", galleryId, expectedVersion)
    }

    fun reissueCollabLink(
        sessionId: Long,
        token: String,
        expiresAt: OffsetDateTime,
        expectedVersion: Long,
    ) {
        val updated = jdbcClient.sql(
            """
            UPDATE collab_sessions
            SET collab_token = :token, revoked_at = NULL, expires_at = :expiresAt,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :sessionId AND version = :expectedVersion AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("token", token)
            .param("expiresAt", expiresAt)
            .param("sessionId", sessionId)
            .param("expectedVersion", expectedVersion)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    fun revokeCollabLink(sessionId: Long, expectedVersion: Long) {
        val updated = jdbcClient.sql(
            """
            UPDATE collab_sessions
            SET revoked_at = CURRENT_TIMESTAMP, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :sessionId AND version = :expectedVersion
              AND deleted_at IS NULL AND revoked_at IS NULL
            """.trimIndent(),
        )
            .param("sessionId", sessionId)
            .param("expectedVersion", expectedVersion)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    fun reopenGallery(galleryId: Long, selectionDeadline: OffsetDateTime, expectedVersion: Long) {
        val updated = jdbcClient.sql(
            """
            UPDATE galleries
            SET status = 'OPEN', workflow_status = 'IN_PROGRESS',
                selection_deadline = :selectionDeadline,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :galleryId AND version = :expectedVersion AND deleted_at IS NULL
              AND status = 'CLOSED' AND :selectionDeadline > CURRENT_TIMESTAMP
            """.trimIndent(),
        )
            .param("selectionDeadline", selectionDeadline)
            .param("galleryId", galleryId)
            .param("expectedVersion", expectedVersion)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    fun createPhotoReplacement(
        photoId: Long,
        storageKey: String,
        originalFileName: String,
        contentType: String,
        expiresAt: ZonedDateTime,
        actorAdminId: Long,
        reason: String,
        expectedVersion: Long,
    ): Long {
        requireVersion("photos", photoId, expectedVersion, lock = true)
        jdbcClient.sql(
            """
            UPDATE admin_photo_replacement_uploads
            SET status = 'EXPIRED'
            WHERE photo_id = :photoId AND status = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("photoId", photoId).update()
        val activePending = jdbcClient.sql(
            """
            SELECT COUNT(*) FROM admin_photo_replacement_uploads
            WHERE photo_id = :photoId AND status = 'PENDING'
            """.trimIndent(),
        ).param("photoId", photoId).query { rs, _ -> rs.getLong(1) }.single()
        if (activePending != 0L) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        val replacementId = jdbcClient.sql(
            """
            INSERT INTO admin_photo_replacement_uploads
                (photo_id, storage_key, original_file_name, content_type, status, expires_at,
                 actor_admin_id, reason, created_at)
            VALUES
                (:photoId, :storageKey, :originalFileName, :contentType, 'PENDING', :expiresAt,
                 :actorAdminId, :reason, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("photoId", photoId)
            .param("storageKey", storageKey)
            .param("originalFileName", originalFileName)
            .param("contentType", contentType)
            .param("expiresAt", expiresAt.toOffsetDateTime())
            .param("actorAdminId", actorAdminId)
            .param("reason", reason)
            .query { rs, _ -> rs.getLong("id") }
            .single()
        bumpVersion("photos", photoId, expectedVersion)
        return replacementId
    }

    fun pendingPhotoReplacementUpload(photoId: Long, replacementId: Long): PendingReplacementUpload = jdbcClient.sql(
        """
        SELECT storage_key, content_type
        FROM admin_photo_replacement_uploads
        WHERE id = :replacementId AND photo_id = :photoId AND status = 'PENDING'
          AND expires_at > CURRENT_TIMESTAMP
        """.trimIndent(),
    )
        .param("replacementId", replacementId)
        .param("photoId", photoId)
        .query { rs, _ -> PendingReplacementUpload(
            storageKey = rs.getString("storage_key"),
            contentType = rs.getString("content_type"),
        ) }
        .optional()
        .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }

    fun pendingPhotoReplacementStorageKey(photoId: Long, replacementId: Long): String =
        pendingPhotoReplacementUpload(photoId, replacementId).storageKey

    fun completePhotoReplacement(
        photoId: Long,
        replacementId: Long,
        actorAdminId: Long,
        reason: String,
        expectedVersion: Long,
    ): PhotoReplacementResult {
        val pending = jdbcClient.sql(
            """
            SELECT id, storage_key, original_file_name, content_type
            FROM admin_photo_replacement_uploads
            WHERE id = :replacementId AND photo_id = :photoId AND status = 'PENDING'
              AND expires_at > CURRENT_TIMESTAMP
            FOR UPDATE
            """.trimIndent(),
        )
            .param("replacementId", replacementId)
            .param("photoId", photoId)
            .query { rs, _ -> PendingReplacement(
                id = rs.getLong("id"),
                storageKey = rs.getString("storage_key"),
                originalFileName = rs.getString("original_file_name"),
                contentType = rs.getString("content_type"),
            ) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
        val current = jdbcClient.sql(
            """
            SELECT gallery_id, storage_key, preview_key, original_file_name, content_type
            FROM photos WHERE id = :photoId AND version = :expectedVersion AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
        )
            .param("photoId", photoId)
            .param("expectedVersion", expectedVersion)
            .query { rs, _ -> PhotoCurrent(
                galleryId = rs.getLong("gallery_id"),
                storageKey = rs.getString("storage_key"),
                previewKey = rs.getString("preview_key"),
                originalFileName = rs.getString("original_file_name"),
                contentType = rs.getString("content_type"),
            ) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT) }
        val maxRevision = jdbcClient.sql(
            "SELECT COALESCE(MAX(revision_number), 0) FROM admin_photo_revisions WHERE photo_id = :photoId",
        ).param("photoId", photoId).query { rs, _ -> rs.getLong(1) }.single()
        if (maxRevision == 0L) {
            jdbcClient.sql(
                """
                INSERT INTO admin_photo_revisions
                    (photo_id, revision_number, storage_key, preview_key, original_file_name, content_type, created_at)
                VALUES (:photoId, 1, :storageKey, :previewKey, :originalFileName, :contentType, CURRENT_TIMESTAMP)
                """.trimIndent(),
            )
                .param("photoId", photoId)
                .param("storageKey", current.storageKey)
                .param("previewKey", current.previewKey)
                .param("originalFileName", current.originalFileName)
                .param("contentType", current.contentType)
                .update()
        }
        val revisionNumber = if (maxRevision == 0L) 2L else maxRevision + 1
        val revisionId = jdbcClient.sql(
            """
            INSERT INTO admin_photo_revisions
                (photo_id, revision_number, storage_key, preview_key, original_file_name, content_type, created_at)
            VALUES (:photoId, :revisionNumber, :storageKey, :previewKey, :originalFileName, :contentType, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("photoId", photoId)
            .param("revisionNumber", revisionNumber)
            .param("storageKey", pending.storageKey)
            .param("previewKey", null)
            .param("originalFileName", pending.originalFileName)
            .param("contentType", pending.contentType)
            .query { rs, _ -> rs.getLong(1) }
            .single()
        val photoUpdated = jdbcClient.sql(
            """
            UPDATE photos
            SET storage_key = :storageKey,
                original_file_name = :originalFileName,
                content_type = :contentType,
                status = 'UPLOADED',
                preview_key = NULL,
                byte_size = NULL,
                width = NULL,
                height = NULL,
                camera_make = NULL,
                camera_model = NULL,
                taken_at = NULL,
                exposure_time = NULL,
                f_number = NULL,
                iso = NULL,
                technical_quality_score = NULL,
                technical_quality_signals = NULL,
                quality_analyzed_at = NULL,
                upload_url_expires_at = NULL,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :photoId AND version = :expectedVersion
            """.trimIndent(),
        )
            .param("storageKey", pending.storageKey)
            .param("originalFileName", pending.originalFileName)
            .param("contentType", pending.contentType)
            .param("photoId", photoId)
            .param("expectedVersion", expectedVersion)
            .update()
        if (photoUpdated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        // 교체된 이미지의 모델 파생값은 전부 무효다. 행을 지우면 임베더가 ON CONFLICT INSERT로 다시 만든다.
        jdbcClient.sql("DELETE FROM photo_analysis WHERE photo_id = :photoId")
            .param("photoId", photoId)
            .update()
        val replacementUpdated = jdbcClient.sql(
            """
            UPDATE admin_photo_replacement_uploads
            SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
            WHERE id = :replacementId AND status = 'PENDING'
            """.trimIndent(),
        ).param("replacementId", replacementId).update()
        if (replacementUpdated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        val jobIds = listOf("DERIVATIVE", "EMBEDDING", "QUALITY_ANALYSIS").map { jobType ->
            createProcessingJob(
                jobType = jobType,
                targetType = AdminResourceType.PHOTO,
                targetId = photoId,
                revisionId = revisionId,
                payload = mapOf("galleryId" to current.galleryId, "storageKey" to pending.storageKey),
                actorAdminId = actorAdminId,
                reason = reason,
            )
        }
        return PhotoReplacementResult(
            revisionId = revisionId,
            revisionNumber = revisionNumber,
            newStorageKey = pending.storageKey,
            galleryId = current.galleryId,
            jobIds = jobIds,
        )
    }

    fun findOperation(operationId: Long): OperationRow? = jdbcClient.sql(
        """
        SELECT id, action, status, target_type, target_id, attempt_count
        FROM admin_idempotency_keys WHERE id = :operationId
        """.trimIndent(),
    )
        .param("operationId", operationId)
        .query { rs, _ -> OperationRow(
            id = rs.getLong("id"),
            action = rs.getString("action"),
            status = rs.getString("status"),
            targetType = rs.getString("target_type"),
            targetId = rs.getString("target_id"),
            attemptCount = rs.getInt("attempt_count"),
        ) }
        .optional()
        .orElse(null)

    fun markOperationPending(operationId: Long): Int = jdbcClient.sql(
        """
        UPDATE admin_idempotency_keys
        SET status = 'PENDING', failure_code = NULL, attempt_count = attempt_count + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = :operationId AND status IN ('FAILED', 'CANCELED')
        """.trimIndent(),
    ).param("operationId", operationId).update()

    fun markOperationCompleted(operationId: Long, result: String) {
        jdbcClient.sql(
            """
            UPDATE admin_idempotency_keys
            SET status = 'COMPLETED', result_payload = :result, updated_at = CURRENT_TIMESTAMP
            WHERE id = :operationId AND status = 'PENDING'
            """.trimIndent(),
        ).param("result", result).param("operationId", operationId).update()
    }

    fun markOperationFailed(operationId: Long, failureCode: String) {
        jdbcClient.sql(
            """
            UPDATE admin_idempotency_keys
            SET status = 'FAILED', failure_code = :failureCode, updated_at = CURRENT_TIMESTAMP
            WHERE id = :operationId AND status = 'PENDING'
            """.trimIndent(),
        ).param("failureCode", failureCode.take(80)).param("operationId", operationId).update()
    }

    fun cancelOperation(operationId: Long): Int = jdbcClient.sql(
        """
        UPDATE admin_idempotency_keys
        SET status = 'CANCELED', updated_at = CURRENT_TIMESTAMP
        WHERE id = :operationId AND status IN ('PENDING', 'FAILED')
        """.trimIndent(),
    ).param("operationId", operationId).update()

    fun createProcessingJob(
        jobType: String,
        targetType: AdminResourceType,
        targetId: Long,
        revisionId: Long?,
        payload: Map<String, Any?>,
        actorAdminId: Long?,
        reason: String,
    ): Long = jdbcClient.sql(
        """
        INSERT INTO admin_processing_jobs
            (job_type, status, target_type, target_id, revision_id, payload,
             attempt_count, actor_admin_id, reason, created_at, updated_at)
        VALUES
            (:jobType, 'PENDING', :targetType, :targetId, :revisionId, CAST(:payload AS JSONB),
             0, :actorAdminId, :reason, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (job_type, target_type, target_id, revision_id) WHERE revision_id IS NOT NULL
        DO UPDATE SET updated_at = admin_processing_jobs.updated_at
        RETURNING id
        """.trimIndent(),
    )
        .param("jobType", jobType)
        .param("targetType", targetType.name)
        .param("targetId", targetId)
        .param("revisionId", revisionId)
        .param("payload", objectMapper.writeValueAsString(payload))
        .param("actorAdminId", actorAdminId)
        .param("reason", reason)
        .query { rs, _ -> rs.getLong(1) }
        .single()

    fun findProcessingJob(jobId: Long): ProcessingJob? = jdbcClient.sql(
        """
        SELECT id, job_type, status, target_type, target_id, revision_id,
               payload::TEXT, attempt_count, failure_code
        FROM admin_processing_jobs WHERE id = :jobId
        """.trimIndent(),
    )
        .param("jobId", jobId)
        .query { rs, _ -> ProcessingJob(
            id = rs.getLong("id"),
            jobType = rs.getString("job_type"),
            status = rs.getString("status"),
            targetType = AdminResourceType.valueOf(rs.getString("target_type")),
            targetId = rs.getLong("target_id"),
            revisionId = rs.getLong("revision_id").takeUnless { rs.wasNull() },
            payload = objectMapper.readValue(rs.getString("payload"), Map::class.java)
                .entries.associate { it.key.toString() to it.value },
            attemptCount = rs.getInt("attempt_count"),
            failureCode = rs.getString("failure_code"),
        ) }
        .optional()
        .orElse(null)

    /** 수동 재시도는 실행 attempt를 소비하지 않고 durable outbox를 새 PENDING 상태로 되돌린다. */
    fun queueProcessingJob(
        jobId: Long,
        actorAdminId: Long,
        reason: String,
    ): ProcessingJob {
        val updated = jdbcClient.sql(
            """
            UPDATE admin_processing_jobs
            SET status = 'PENDING', attempt_count = 0,
                failure_code = NULL, last_run_at = NULL,
                actor_admin_id = :actorAdminId, reason = :reason,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :jobId
              AND (
                status IN ('PENDING', 'FAILED')
                OR (status = 'DISPATCHING' AND failure_code = :claimedNotSent)
              )
            RETURNING id, job_type, status, target_type, target_id, revision_id,
                      payload::TEXT, attempt_count, failure_code
            """.trimIndent(),
        )
            .param("jobId", jobId)
            .param("actorAdminId", actorAdminId)
            .param("reason", reason)
            .param("claimedNotSent", AdminWorkflowExecutionRepository.CLAIMED_NOT_SENT)
            .query { rs, _ -> ProcessingJob(
                id = rs.getLong("id"),
                jobType = rs.getString("job_type"),
                status = rs.getString("status"),
                targetType = AdminResourceType.valueOf(rs.getString("target_type")),
                targetId = rs.getLong("target_id"),
                revisionId = rs.getLong("revision_id").takeUnless { rs.wasNull() },
                payload = objectMapper.readValue(rs.getString("payload"), Map::class.java)
                    .entries.associate { it.key.toString() to it.value },
                attemptCount = rs.getInt("attempt_count"),
                failureCode = rs.getString("failure_code"),
            ) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS) }
        return updated
    }

    fun cancelProcessingJob(jobId: Long): Int = jdbcClient.sql(
        """
        UPDATE admin_processing_jobs
        SET status = 'CANCELED', failure_code = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE id = :jobId
          AND (
            status IN ('PENDING', 'FAILED')
            OR (status = 'DISPATCHING' AND failure_code = :claimedNotSent)
          )
        """.trimIndent(),
    )
        .param("jobId", jobId)
        .param("claimedNotSent", AdminWorkflowExecutionRepository.CLAIMED_NOT_SENT)
        .update()

    fun bumpResourceVersion(type: AdminResourceType, id: Long, expectedVersion: Long) {
        val (table, idColumn) = when (type) {
            AdminResourceType.USER -> "users" to "id"
            AdminResourceType.WORKSPACE -> "workspaces" to "id"
            AdminResourceType.STUDIO -> "studios" to "workspace_id"
            AdminResourceType.GALLERY -> "galleries" to "id"
            AdminResourceType.PHOTO -> "photos" to "id"
            AdminResourceType.CONCEPT_FOLDER -> "concept_folders" to "id"
            AdminResourceType.DETAIL_FOLDER -> "detail_folders" to "id"
            AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT -> "photo_category_assignments" to "photo_id"
            AdminResourceType.CATEGORIZATION_JOB -> "categorization_jobs" to "id"
            AdminResourceType.PHOTO_RATING -> "photo_ratings" to "photo_id"
            AdminResourceType.SELECTION -> "photo_selections" to "id"
            AdminResourceType.COLLABORATION -> "collab_sessions" to "id"
            AdminResourceType.ALBUM -> "photo_folder_groups" to "id"
            AdminResourceType.RETOUCH_REQUEST -> "retouch_rounds" to "id"
        }
        bumpVersion(table, id, expectedVersion, idColumn)
    }

    fun createAiSelectionDraft(
        selectionId: Long,
        actorAdminId: Long,
        reason: String,
        expectedVersion: Long,
        requestedCount: Int?,
    ): AiSelectionDraftResult {
        val galleryId = selectionGalleryId(selectionId, expectedVersion)
        val configuredLimit = jdbcClient.sql(
            "SELECT COALESCE(max_selectable_photo_count, 50) FROM galleries WHERE id = :galleryId AND deleted_at IS NULL",
        ).param("galleryId", galleryId).query { rs, _ -> rs.getInt(1) }.optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
        val limit = (requestedCount ?: configuredLimit).coerceAtMost(configuredLimit)
        if (limit !in 1..1000) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        val inputConditions = mapOf(
            "algorithm" to "DETERMINISTIC_DIVERSE_V2_TECHNICAL_QUALITY",
            "galleryId" to galleryId,
            "requestedCount" to limit,
            "qualityWeight" to QUALITY_WEIGHT,
            "diversityWeight" to DIVERSITY_WEIGHT,
            "requiresEmbedding" to true,
        )
        val jobId = jdbcClient.sql(
            """
            INSERT INTO admin_ai_selection_jobs
                (selection_id, status, input_conditions, attempt_count, actor_admin_id, reason,
                 created_at, started_at, updated_at)
            VALUES
                (:selectionId, 'RUNNING', CAST(:inputConditions AS JSONB), 1, :actorAdminId, :reason,
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("selectionId", selectionId)
            .param("inputConditions", objectMapper.writeValueAsString(inputConditions))
            .param("actorAdminId", actorAdminId)
            .param("reason", reason)
            .query { rs, _ -> rs.getLong(1) }.single()
        val candidates = jdbcClient.sql(
            """
            SELECT p.id, pa.embedding::TEXT AS embedding,
                   COALESCE(AVG(r.score), 0.0) AS rating,
                   COALESCE(p.width::BIGINT * p.height::BIGINT, 0) AS pixels,
                   p.technical_quality_score
            FROM photos p
            JOIN photo_analysis pa ON pa.photo_id = p.id
            LEFT JOIN photo_ratings r ON r.photo_id = p.id
            WHERE p.gallery_id = :galleryId AND p.deleted_at IS NULL
              AND p.status = 'EMBEDDED' AND pa.embedding IS NOT NULL
            GROUP BY p.id, pa.embedding, p.width, p.height, p.technical_quality_score
            ORDER BY p.id
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .query { rs, _ -> AiCandidate(
                photoId = rs.getLong("id"),
                embedding = parseVector(rs.getString("embedding")),
                rating = rs.getDouble("rating"),
                pixels = rs.getLong("pixels"),
                technicalQualityScore = rs.getObject("technical_quality_score")
                    ?.let { value -> (value as Number).toDouble() },
            ) }
            .list()
        if (candidates.isEmpty()) {
            jdbcClient.sql(
                """
                UPDATE admin_ai_selection_jobs
                SET status = 'FAILED', failure_code = 'NO_EMBEDDED_CANDIDATES',
                    completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :jobId
                """.trimIndent(),
            ).param("jobId", jobId).update()
            bumpVersion("photo_selections", selectionId, expectedVersion)
            return AiSelectionDraftResult(jobId, "FAILED", null, emptyList(), "NO_EMBEDDED_CANDIDATES")
        }
        val photoIds = selectDiverse(candidates, limit)
        val items = photoIds.mapIndexed { index, photoId -> SelectionItem(photoId, null, index) }
        val revisionId = insertSelectionRevision(
            selectionId = selectionId,
            source = "AI_DRAFT",
            status = "SELECTING",
            items = items,
            actorAdminId = actorAdminId,
            reason = reason,
        )
        jdbcClient.sql(
            """
            UPDATE admin_ai_selection_jobs
            SET status = 'SUCCEEDED', result_revision_id = :revisionId,
                completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :jobId AND status = 'RUNNING'
            """.trimIndent(),
        ).param("revisionId", revisionId).param("jobId", jobId).update()
        bumpVersion("photo_selections", selectionId, expectedVersion)
        return AiSelectionDraftResult(jobId, "SUCCEEDED", revisionId, photoIds, null)
    }

    fun replaceSelectionItems(
        selectionId: Long,
        photoIds: List<Long>,
        actorAdminId: Long,
        reason: String,
        expectedVersion: Long,
    ): SelectionRevisionResult {
        val galleryId = selectionGalleryId(selectionId, expectedVersion)
        validatePhotosInGallery(galleryId, photoIds)
        val previousRevisionId = snapshotSelection(selectionId, actorAdminId, "변경 전: $reason")

        jdbcClient.sql("DELETE FROM photo_selection_items WHERE selection_id = :selectionId")
            .param("selectionId", selectionId)
            .update()
        photoIds.distinct().forEachIndexed { index, photoId ->
            jdbcClient.sql(
                """
                INSERT INTO photo_selection_items
                    (selection_id, photo_id, version, created_at, updated_at)
                VALUES (:selectionId, :photoId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
            )
                .param("selectionId", selectionId)
                .param("photoId", photoId)
                .update()
        }
        jdbcClient.sql(
            """
            UPDATE photo_selections
            SET status = 'SELECTING', submitted_at = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :selectionId AND version = :expectedVersion
            """.trimIndent(),
        )
            .param("selectionId", selectionId)
            .param("expectedVersion", expectedVersion)
            .update()
        val revisionId = insertSelectionRevision(
            selectionId = selectionId,
            source = "ADMIN",
            status = "SELECTING",
            items = photoIds.distinct().mapIndexed { index, photoId -> SelectionItem(photoId, null, index) },
            actorAdminId = actorAdminId,
            reason = reason,
        )
        return SelectionRevisionResult(
            revisionId = revisionId,
            photoIds = photoIds.distinct(),
            previousRevisionId = previousRevisionId,
        )
    }

    fun submitSelectionRevision(
        selectionId: Long,
        revisionId: Long?,
        actorAdminId: Long,
        reason: String,
        expectedVersion: Long,
    ): SelectionRevisionResult {
        val galleryId = selectionGalleryId(selectionId, expectedVersion)
        val items = if (revisionId == null) {
            selectionItems(selectionId)
        } else {
            loadSelectionRevision(selectionId, revisionId)
        }
        if (items.isEmpty()) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        validatePhotosInGallery(galleryId, items.map(SelectionItem::photoId))
        val previousRevisionId = snapshotSelection(selectionId, actorAdminId, "제출 전: $reason")
        if (revisionId != null) {
            jdbcClient.sql("DELETE FROM photo_selection_items WHERE selection_id = :selectionId")
                .param("selectionId", selectionId).update()
            items.forEach { item ->
                jdbcClient.sql(
                    """
                    INSERT INTO photo_selection_items
                        (selection_id, photo_id, retouch_photo_id, version, created_at, updated_at)
                    VALUES (:selectionId, :photoId, :retouchPhotoId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.trimIndent(),
                )
                    .param("selectionId", selectionId)
                    .param("photoId", item.photoId)
                    .param("retouchPhotoId", item.retouchPhotoId)
                    .update()
            }
        }
        jdbcClient.sql(
            """
            UPDATE photo_selections
            SET status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :selectionId AND version = :expectedVersion
            """.trimIndent(),
        )
            .param("selectionId", selectionId)
            .param("expectedVersion", expectedVersion)
            .update()
        val submittedRevisionId = insertSelectionRevision(
            selectionId = selectionId,
            source = "ADMIN",
            status = "SUBMITTED",
            items = items,
            actorAdminId = actorAdminId,
            reason = reason,
        )
        val mockJobId = createProcessingJob(
            jobType = "MOCK_RECALCULATION",
            targetType = AdminResourceType.SELECTION,
            targetId = selectionId,
            revisionId = submittedRevisionId,
            payload = mapOf("galleryId" to galleryId, "selectionRevisionId" to submittedRevisionId),
            actorAdminId = actorAdminId,
            reason = reason,
        )
        return SelectionRevisionResult(
            revisionId = submittedRevisionId,
            photoIds = items.map(SelectionItem::photoId),
            mockJobId = mockJobId,
            previousRevisionId = previousRevisionId,
        )
    }

    fun withdrawSelection(
        selectionId: Long,
        actorAdminId: Long,
        reason: String,
        expectedVersion: Long,
    ): Long {
        selectionGalleryId(selectionId, expectedVersion)
        snapshotSelection(selectionId, actorAdminId, "제출 취소 전: $reason")
        val updated = jdbcClient.sql(
            """
            UPDATE photo_selections
            SET status = 'SELECTING', submitted_at = NULL,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :selectionId AND version = :expectedVersion AND status = 'SUBMITTED'
            """.trimIndent(),
        )
            .param("selectionId", selectionId)
            .param("expectedVersion", expectedVersion)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        return insertSelectionRevision(
            selectionId = selectionId,
            source = "ADMIN",
            status = "SELECTING",
            items = selectionItems(selectionId),
            actorAdminId = actorAdminId,
            reason = reason,
        )
    }

    private fun selectionGalleryId(selectionId: Long, expectedVersion: Long): Long = jdbcClient.sql(
        "SELECT gallery_id FROM photo_selections WHERE id = :selectionId AND version = :expectedVersion AND deleted_at IS NULL FOR UPDATE",
    )
        .param("selectionId", selectionId)
        .param("expectedVersion", expectedVersion)
        .query { rs, _ -> rs.getLong("gallery_id") }
        .optional()
        .orElseThrow { AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT) }

    private fun validatePhotosInGallery(galleryId: Long, photoIds: List<Long>) {
        if (photoIds.isEmpty() || photoIds.size != photoIds.distinct().size) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val count = jdbcClient.sql(
            "SELECT COUNT(*) FROM photos WHERE gallery_id = :galleryId AND id IN (:photoIds) AND deleted_at IS NULL AND status <> 'PENDING'",
        )
            .param("galleryId", galleryId)
            .param("photoIds", photoIds)
            .query { rs, _ -> rs.getLong(1) }
            .single()
        if (count != photoIds.size.toLong()) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        val maxCount = jdbcClient.sql(
            "SELECT COALESCE(max_selectable_photo_count, 2147483647) FROM galleries WHERE id = :galleryId",
        ).param("galleryId", galleryId)
            .query { rs, _ -> rs.getInt(1) }
            .single()
        if (photoIds.size > maxCount) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
    }

    fun requireRetryableAiJob(selectionId: Long, jobId: Long) {
        val count = jdbcClient.sql(
            """
            SELECT COUNT(*) FROM admin_ai_selection_jobs
            WHERE id = :jobId AND selection_id = :selectionId AND status IN ('FAILED', 'CANCELED')
            """.trimIndent(),
        ).param("jobId", jobId).param("selectionId", selectionId)
            .query { rs, _ -> rs.getLong(1) }.single()
        if (count != 1L) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
    }

    fun cancelAiSelectionJob(selectionId: Long, jobId: Long): Int = jdbcClient.sql(
        """
        UPDATE admin_ai_selection_jobs
        SET status = 'CANCELED', completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
        WHERE id = :jobId AND selection_id = :selectionId AND status IN ('PENDING', 'FAILED')
        """.trimIndent(),
    ).param("jobId", jobId).param("selectionId", selectionId).update()

    private fun snapshotSelection(selectionId: Long, actorAdminId: Long, reason: String): Long {
        val status = jdbcClient.sql("SELECT status FROM photo_selections WHERE id = :selectionId")
            .param("selectionId", selectionId)
            .query { rs, _ -> rs.getString("status") }
            .single()
        val revisionId = insertSelectionRevision(
            selectionId = selectionId,
            source = "ADMIN",
            status = status,
            items = selectionItems(selectionId),
            actorAdminId = actorAdminId,
            reason = reason,
        )
        // 기존 산출물은 변경 후 선택이 아니라 바로 직전 선택에 귀속돼야 한다. 이미 귀속된
        // 산출물은 후속 관리자 변경에서도 절대 새 리비전으로 이동시키지 않는다.
        jdbcClient.sql(
            """
            UPDATE retouch_rounds r
            SET selection_revision_id = :revisionId,
                version = r.version + 1,
                updated_at = CURRENT_TIMESTAMP
            FROM photo_selections s
            WHERE s.id = :selectionId AND r.gallery_id = s.gallery_id
              AND r.selection_revision_id IS NULL
              AND r.status IN ('REQUESTED', 'COMPLETED')
            """.trimIndent(),
        )
            .param("revisionId", revisionId)
            .param("selectionId", selectionId)
            .update()
        jdbcClient.sql(
            """
            UPDATE photo_folder_groups g
            SET selection_revision_id = :revisionId,
                version = g.version + 1,
                updated_at = CURRENT_TIMESTAMP
            FROM photo_selections s
            WHERE s.id = :selectionId AND g.gallery_id = s.gallery_id
              AND g.selection_revision_id IS NULL
              AND EXISTS (SELECT 1 FROM photo_folder_items i WHERE i.group_id = g.id)
            """.trimIndent(),
        )
            .param("revisionId", revisionId)
            .param("selectionId", selectionId)
            .update()
        return revisionId
    }

    private fun insertSelectionRevision(
        selectionId: Long,
        source: String,
        status: String,
        items: List<SelectionItem>,
        actorAdminId: Long,
        reason: String,
    ): Long {
        val revisionNumber = jdbcClient.sql(
            "SELECT COALESCE(MAX(revision_number), 0) + 1 FROM admin_selection_revisions WHERE selection_id = :selectionId",
        ).param("selectionId", selectionId).query { rs, _ -> rs.getLong(1) }.single()
        return jdbcClient.sql(
            """
            INSERT INTO admin_selection_revisions
                (selection_id, revision_number, source, status, photo_items, actor_admin_id, reason, created_at)
            VALUES
                (:selectionId, :revisionNumber, :source, :status, CAST(:photoItems AS JSONB), :actorAdminId, :reason, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("selectionId", selectionId)
            .param("revisionNumber", revisionNumber)
            .param("source", source)
            .param("status", status)
            .param("photoItems", objectMapper.writeValueAsString(items))
            .param("actorAdminId", actorAdminId)
            .param("reason", reason)
            .query { rs, _ -> rs.getLong("id") }
            .single()
    }

    private fun selectionItems(selectionId: Long): List<SelectionItem> = jdbcClient.sql(
        """
        SELECT photo_id, retouch_photo_id
        FROM photo_selection_items WHERE selection_id = :selectionId ORDER BY id
        """.trimIndent(),
    )
        .param("selectionId", selectionId)
        .query { rs, index -> SelectionItem(
            photoId = rs.getLong("photo_id"),
            retouchPhotoId = rs.getLong("retouch_photo_id").takeUnless { rs.wasNull() },
            sortOrder = index,
        ) }
        .list()

    private fun loadSelectionRevision(selectionId: Long, revisionId: Long): List<SelectionItem> {
        val json = jdbcClient.sql(
            "SELECT photo_items::TEXT FROM admin_selection_revisions WHERE id = :revisionId AND selection_id = :selectionId",
        )
            .param("revisionId", revisionId)
            .param("selectionId", selectionId)
            .query { rs, _ -> rs.getString(1) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
        return objectMapper.readValue(
            json,
            objectMapper.typeFactory.constructCollectionType(List::class.java, SelectionItem::class.java),
        )
    }

    private fun parseVector(raw: String): DoubleArray = raw
        .removePrefix("[")
        .removeSuffix("]")
        .split(',')
        .map { component -> component.trim().toDouble() }
        .toDoubleArray()

    private fun selectDiverse(candidates: List<AiCandidate>, limit: Int): List<Long> {
        val maxPixels = candidates.maxOf(AiCandidate::pixels).coerceAtLeast(1)
        val scored = candidates.map { candidate ->
            val ratingScore = (candidate.rating / 5.0).coerceIn(0.0, 1.0)
            val resolutionScore = kotlin.math.ln1p(candidate.pixels.toDouble()) / kotlin.math.ln1p(maxPixels.toDouble())
            val legacyQuality = ratingScore * 0.8 + resolutionScore * 0.2
            val quality = candidate.technicalQualityScore
                ?.takeIf(Double::isFinite)
                ?.div(100.0)
                ?.coerceIn(0.0, 1.0)
                ?: legacyQuality
            candidate.copy(quality = quality)
        }
        val remaining = scored.toMutableList()
        val selected = mutableListOf<AiCandidate>()
        while (remaining.isNotEmpty() && selected.size < limit) {
            val next = remaining.maxWithOrNull(
                compareBy<AiCandidate> { candidate ->
                    val diversity = if (selected.isEmpty()) {
                        1.0
                    } else {
                        selected.minOf { chosen -> cosineDistance(candidate.embedding, chosen.embedding) }
                    }
                    candidate.quality * QUALITY_WEIGHT + diversity * DIVERSITY_WEIGHT
                }.thenBy { candidate -> -candidate.photoId },
            ) ?: break
            selected += next
            remaining.remove(next)
        }
        return selected.map(AiCandidate::photoId)
    }

    private fun cosineDistance(left: DoubleArray, right: DoubleArray): Double {
        if (left.size != right.size || left.isEmpty()) return 0.0
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
        return (1.0 - dot / (kotlin.math.sqrt(leftNorm) * kotlin.math.sqrt(rightNorm))).coerceIn(0.0, 2.0) / 2.0
    }

    fun createCollabComment(
        sessionId: Long,
        photoId: Long,
        guestId: Long,
        content: String,
        expectedVersion: Long,
    ): Long {
        requireVersion("collab_sessions", sessionId, expectedVersion)
        val commentId = jdbcClient.sql(
            """
            INSERT INTO collab_photo_comments
                (collab_session_id, photo_id, collab_guest_id, content, version, created_at, updated_at)
            SELECT :sessionId, :photoId, :guestId, :content, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            WHERE ${sharedPhotoExistsSql(":sessionId", ":photoId")}
              AND EXISTS (SELECT 1 FROM collab_guests WHERE id = :guestId AND collab_session_id = :sessionId)
            RETURNING id
            """.trimIndent(),
        )
            .param("photoId", photoId)
            .param("guestId", guestId)
            .param("content", content)
            .param("sessionId", sessionId)
            .query { rs, _ -> rs.getLong("id") }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS) }
        bumpVersion("collab_sessions", sessionId, expectedVersion)
        return commentId
    }

    fun updateCollabComment(
        sessionId: Long,
        commentId: Long,
        content: String,
        expectedVersion: Long,
        expectedChildVersion: Long,
    ) {
        requireVersion("collab_sessions", sessionId, expectedVersion)
        val updated = jdbcClient.sql(
            """
            UPDATE collab_photo_comments c
            SET content = :content, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE c.id = :commentId AND c.deleted_at IS NULL
              AND c.version = :expectedChildVersion
              AND c.collab_session_id = :sessionId
            """.trimIndent(),
        )
            .param("content", content)
            .param("commentId", commentId)
            .param("sessionId", sessionId)
            .param("expectedChildVersion", expectedChildVersion)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        bumpVersion("collab_sessions", sessionId, expectedVersion)
    }

    fun setCollabCommentDeleted(sessionId: Long, commentId: Long, deleted: Boolean, expectedVersion: Long) {
        requireVersion("collab_sessions", sessionId, expectedVersion)
        val deletedValue = if (deleted) "CURRENT_TIMESTAMP" else "NULL"
        val predicate = if (deleted) "c.deleted_at IS NULL" else "c.deleted_at IS NOT NULL"
        val updated = jdbcClient.sql(
            """
            UPDATE collab_photo_comments c
            SET deleted_at = $deletedValue, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE c.id = :commentId AND $predicate
              AND c.collab_session_id = :sessionId
            """.trimIndent(),
        )
            .param("commentId", commentId)
            .param("sessionId", sessionId)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        bumpVersion("collab_sessions", sessionId, expectedVersion)
    }

    fun addCollabLike(
        sessionId: Long,
        photoId: Long,
        guestId: Long,
        expectedVersion: Long,
    ): Long {
        requireVersion("collab_sessions", sessionId, expectedVersion)
        val likeId = jdbcClient.sql(
            """
            INSERT INTO collab_photo_likes
                (collab_session_id, photo_id, collab_guest_id, version, created_at, updated_at)
            SELECT :sessionId, :photoId, :guestId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            WHERE ${sharedPhotoExistsSql(":sessionId", ":photoId")}
              AND EXISTS (SELECT 1 FROM collab_guests WHERE id = :guestId AND collab_session_id = :sessionId)
            ON CONFLICT (collab_session_id, photo_id, collab_guest_id) WHERE deleted_at IS NULL DO NOTHING
            RETURNING id
            """.trimIndent(),
        )
            .param("photoId", photoId)
            .param("guestId", guestId)
            .param("sessionId", sessionId)
            .query { rs, _ -> rs.getLong("id") }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS) }
        bumpVersion("collab_sessions", sessionId, expectedVersion)
        return likeId
    }

    fun findActiveCollabLikeId(sessionId: Long, photoId: Long, guestId: Long): Long =
        jdbcClient.sql(
            """
            SELECT l.id
            FROM collab_photo_likes l
            JOIN collab_guests g ON g.id = l.collab_guest_id
            WHERE l.photo_id = :photoId
              AND l.collab_guest_id = :guestId
              AND l.deleted_at IS NULL
              AND l.collab_session_id = :sessionId
              AND g.collab_session_id = :sessionId
            """.trimIndent(),
        )
            .param("photoId", photoId)
            .param("guestId", guestId)
            .param("sessionId", sessionId)
            .query { rs, _ -> rs.getLong("id") }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }

    fun createAlbumTemplate(
        albumId: Long,
        name: String,
        layout: Map<String, Any?>,
        expectedVersion: Long,
    ): Long {
        validateAlbumTemplateLayout(layout)
        val scope = jdbcClient.sql(
            """
            SELECT album.id, gallery.workspace_id
            FROM photo_folder_groups album
            JOIN galleries gallery ON gallery.id = album.gallery_id
            WHERE album.id = :albumId AND album.version = :expectedVersion
              AND album.deleted_at IS NULL AND gallery.deleted_at IS NULL
              AND album.template_id IS NULL
            FOR UPDATE OF album
            """.trimIndent(),
        )
            .param("albumId", albumId)
            .param("expectedVersion", expectedVersion)
            .query { rs, _ -> AlbumScope(rs.getLong("id"), rs.getLong("workspace_id")) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT) }
        val templateId = jdbcClient.sql(
            """
            INSERT INTO admin_album_templates (studio_id, name, layout_json, version, created_at, updated_at)
            VALUES (:studioId, :name, CAST(:layout AS JSONB), 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("studioId", scope.studioId).param("name", name)
            .param("layout", objectMapper.writeValueAsString(layout))
            .query { rs, _ -> rs.getLong(1) }.single()
        val assigned = jdbcClient.sql(
            """
            UPDATE photo_folder_groups
            SET template_id = :templateId, template_name = :name,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :albumId AND version = :expectedVersion
              AND deleted_at IS NULL AND template_id IS NULL
            """.trimIndent(),
        )
            .param("templateId", templateId)
            .param("name", name)
            .param("albumId", albumId)
            .param("expectedVersion", expectedVersion)
            .update()
        if (assigned != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        return templateId
    }

    fun updateAlbumTemplate(
        albumId: Long,
        templateId: Long,
        templateExpectedVersion: Long,
        name: String,
        layout: Map<String, Any?>,
        expectedVersion: Long,
    ) {
        validateAlbumTemplateLayout(layout)
        val scope = jdbcClient.sql(
            """
            SELECT album.id, gallery.workspace_id
            FROM photo_folder_groups album
            JOIN galleries gallery ON gallery.id = album.gallery_id
            JOIN admin_album_templates template ON template.id = album.template_id
            WHERE album.id = :albumId AND album.version = :expectedVersion
              AND album.deleted_at IS NULL AND gallery.deleted_at IS NULL
              AND template.id = :templateId AND template.version = :templateExpectedVersion
              AND template.deleted_at IS NULL AND template.studio_id = gallery.workspace_id
            FOR UPDATE OF album, template
            """.trimIndent(),
        )
            .param("templateId", templateId)
            .param("templateExpectedVersion", templateExpectedVersion)
            .param("albumId", albumId)
            .param("expectedVersion", expectedVersion)
            .query { rs, _ -> AlbumScope(rs.getLong("id"), rs.getLong("workspace_id")) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT) }
        val updated = jdbcClient.sql(
            """
            UPDATE admin_album_templates
            SET name = :name, layout_json = CAST(:layout AS JSONB),
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :templateId AND version = :templateExpectedVersion
              AND studio_id = :studioId AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("name", name)
            .param("layout", objectMapper.writeValueAsString(layout))
            .param("templateId", templateId)
            .param("templateExpectedVersion", templateExpectedVersion)
            .param("studioId", scope.studioId)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        val referencesUpdated = jdbcClient.sql(
            """
            UPDATE photo_folder_groups album
            SET template_name = :name, version = album.version + 1, updated_at = CURRENT_TIMESTAMP
            FROM galleries gallery
            WHERE album.gallery_id = gallery.id AND gallery.workspace_id = :studioId
              AND album.template_id = :templateId
            """.trimIndent(),
        )
            .param("name", name)
            .param("studioId", scope.studioId)
            .param("templateId", templateId)
            .update()
        if (referencesUpdated < 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    fun replaceAlbumLayout(
        albumId: Long,
        templateId: Long?,
        selectionRevisionId: Long?,
        folders: List<AlbumFolder>,
        expectedVersion: Long,
    ): Int {
        validateAlbumLayoutContract(folders)
        val scope = jdbcClient.sql(
            """
            SELECT album.gallery_id, gallery.workspace_id
            FROM photo_folder_groups album
            JOIN galleries gallery ON gallery.id = album.gallery_id
            WHERE album.id = :albumId AND album.version = :expectedVersion
              AND album.deleted_at IS NULL AND gallery.deleted_at IS NULL
            FOR UPDATE OF album
            """.trimIndent(),
        )
            .param("albumId", albumId)
            .param("expectedVersion", expectedVersion)
            .query { rs, _ -> AlbumScope(rs.getLong("gallery_id"), rs.getLong("workspace_id")) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT) }
        val photoIds = folders.flatMap { folder -> folder.items.map(AlbumItem::photoId) }
        if (photoIds.size != photoIds.distinct().size) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        if (photoIds.isNotEmpty()) validatePhotosInGallery(scope.albumOrGalleryId, photoIds)
        if (selectionRevisionId != null) {
            val valid = jdbcClient.sql(
                """
                SELECT COUNT(*) FROM admin_selection_revisions r
                JOIN photo_selections s ON s.id = r.selection_id
                WHERE r.id = :revisionId AND s.gallery_id = :galleryId
                """.trimIndent(),
            ).param("revisionId", selectionRevisionId).param("galleryId", scope.albumOrGalleryId)
                .query { rs, _ -> rs.getLong(1) }.single()
            if (valid != 1L) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val templateName = templateId?.let { selectedTemplateId ->
            jdbcClient.sql(
                """
                SELECT t.name
                FROM admin_album_templates t
                WHERE t.id = :templateId AND t.studio_id = :studioId AND t.deleted_at IS NULL
                FOR SHARE OF t
                """.trimIndent(),
            ).param("templateId", selectedTemplateId).param("studioId", scope.studioId)
                .query { rs, _ -> rs.getString(1) }
                .optional()
                .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
        }
        jdbcClient.sql("DELETE FROM photo_folders WHERE group_id = :albumId")
            .param("albumId", albumId).update()
        var itemCount = 0
        folders.forEach { folder ->
            val folderId = jdbcClient.sql(
                """
                INSERT INTO photo_folders (group_id, gallery_id, name, version, created_at, updated_at)
                VALUES (:albumId, :galleryId, :name, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """.trimIndent(),
            )
                .param("albumId", albumId)
                .param("galleryId", scope.albumOrGalleryId)
                .param("name", folder.name)
                .query { rs, _ -> rs.getLong("id") }.single()
            folder.items.forEach { item ->
                jdbcClient.sql(
                    """
                    INSERT INTO photo_folder_items
                        (group_id, folder_id, photo_id, sort_order, crop_json, version, created_at, updated_at)
                    VALUES
                        (:albumId, :folderId, :photoId, :sortOrder, CAST(:cropJson AS JSONB), 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.trimIndent(),
                )
                    .param("albumId", albumId)
                    .param("folderId", folderId)
                    .param("photoId", item.photoId)
                    .param("sortOrder", item.sortOrder)
                    .param("cropJson", item.crop?.let(objectMapper::writeValueAsString) ?: "null")
                    .update()
                itemCount++
            }
        }
        val updated = jdbcClient.sql(
            """
            UPDATE photo_folder_groups
            SET template_id = :templateId, template_name = :templateName,
                selection_revision_id = :selectionRevisionId,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :albumId AND version = :expectedVersion
            """.trimIndent(),
        )
            .param("templateName", templateName)
            .param("templateId", templateId)
            .param("selectionRevisionId", selectionRevisionId)
            .param("albumId", albumId)
            .param("expectedVersion", expectedVersion)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        return itemCount
    }

    fun createRetouchItem(
        roundId: Long,
        photoId: Long,
        requestText: String?,
        structuredAiMetadata: Map<String, Any?>?,
        expectedVersion: Long,
    ): Long {
        val galleryId = jdbcClient.sql(
            """
            SELECT gallery_id FROM retouch_rounds
            WHERE id = :roundId AND version = :expectedVersion
              AND status = 'DRAFTING' AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
        ).param("roundId", roundId).param("expectedVersion", expectedVersion)
            .query { rs, _ -> rs.getLong(1) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT) }
        validatePhotosInGallery(galleryId, listOf(photoId))
        val itemId = jdbcClient.sql(
            """
            INSERT INTO retouch_photos
                (round_id, gallery_id, photo_id, request_text, structured_ai_metadata,
                 version, created_at, updated_at)
            VALUES
                (:roundId, :galleryId, :photoId, :requestText,
                 CAST(NULLIF(:structuredAiMetadata, '') AS JSONB), 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("roundId", roundId)
            .param("galleryId", galleryId)
            .param("photoId", photoId)
            .param("requestText", requestText)
            .param("structuredAiMetadata", structuredAiMetadata?.let(objectMapper::writeValueAsString) ?: "")
            .query { rs, _ -> rs.getLong(1) }.single()
        bumpVersion("retouch_rounds", roundId, expectedVersion)
        return itemId
    }

    fun updateRetouchItem(
        roundId: Long,
        retouchPhotoId: Long,
        patch: Map<String, Any?>,
        expectedVersion: Long,
        expectedChildVersion: Long,
    ) {
        val columns = mapOf(
            "requestText" to "request_text",
            "annotationKey" to "annotation_key",
            "resultKey" to "result_key",
            "resultContentType" to "result_content_type",
            "structuredAiMetadata" to "structured_ai_metadata",
        )
        if (patch.isEmpty() || patch.keys.any { it !in columns }) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val artifactFields = setOf("annotationKey", "resultKey", "resultContentType")
        if (artifactFields.any { name -> patch[name] != null }) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        if (patch.containsKey("resultKey") != patch.containsKey("resultContentType")) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val draftingFields = setOf("requestText", "annotationKey", "structuredAiMetadata")
        val requestedFields = setOf("resultKey", "resultContentType")
        val requiredStatus = when {
            patch.keys.all(draftingFields::contains) -> "DRAFTING"
            patch.keys.all(requestedFields::contains) -> "REQUESTED"
            else -> throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val lockedRound = jdbcClient.sql(
            """
            SELECT id FROM retouch_rounds
            WHERE id = :roundId AND version = :expectedVersion
              AND status = :requiredStatus AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
        )
            .param("roundId", roundId)
            .param("expectedVersion", expectedVersion)
            .param("requiredStatus", requiredStatus)
            .query { rs, _ -> rs.getLong("id") }
            .optional()
            .orElse(null)
        if (lockedRound == null) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        val assignments = patch.entries.joinToString { (name, value) ->
            when {
                value == null -> "${columns.getValue(name)} = NULL"
                name == "structuredAiMetadata" -> "${columns.getValue(name)} = CAST(:$name AS JSONB)"
                else -> "${columns.getValue(name)} = :$name"
            }
        }
        var statement = jdbcClient.sql(
            """
            UPDATE retouch_photos
            SET $assignments,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :retouchPhotoId AND round_id = :roundId AND deleted_at IS NULL
              AND version = :expectedChildVersion
            """.trimIndent(),
        )
            .param("retouchPhotoId", retouchPhotoId)
            .param("roundId", roundId)
            .param("expectedChildVersion", expectedChildVersion)
        patch.filterValues { value -> value != null }.forEach { (name, value) ->
            statement = statement.param(
                name,
                if (name == "structuredAiMetadata") {
                    (value as? Map<*, *>)?.let(objectMapper::writeValueAsString)
                        ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
                } else {
                    value
                },
            )
        }
        val updated = statement.update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        bumpVersion("retouch_rounds", roundId, expectedVersion)
    }

    fun setRetouchItemDeleted(
        roundId: Long,
        retouchPhotoId: Long,
        deleted: Boolean,
        expectedVersion: Long,
    ) {
        requireVersion("retouch_rounds", roundId, expectedVersion)
        val deletedValue = if (deleted) "CURRENT_TIMESTAMP" else "NULL"
        val predicate = if (deleted) "deleted_at IS NULL" else "deleted_at IS NOT NULL"
        val updated = jdbcClient.sql(
            """
            UPDATE retouch_photos
            SET deleted_at = $deletedValue, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :retouchPhotoId AND round_id = :roundId AND $predicate
            """.trimIndent(),
        ).param("retouchPhotoId", retouchPhotoId).param("roundId", roundId).update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        bumpVersion("retouch_rounds", roundId, expectedVersion)
    }

    fun updateRetouchDelivery(
        roundId: Long,
        consented: Boolean,
        delivered: Boolean,
        deliveryNote: String?,
        selectionRevisionId: Long?,
        expectedVersion: Long,
    ) {
        val revisionValue = if (selectionRevisionId == null) "NULL" else ":selectionRevisionId"
        val revisionPredicate = if (selectionRevisionId == null) {
            ""
        } else {
            """
            AND EXISTS (
                SELECT 1 FROM admin_selection_revisions sr
                JOIN photo_selections s ON s.id = sr.selection_id
                WHERE sr.id = :selectionRevisionId AND s.gallery_id = r.gallery_id
            )
            """.trimIndent()
        }
        var statement = jdbcClient.sql(
            """
            UPDATE retouch_rounds r
            SET customer_consented_at = CASE WHEN :consented THEN COALESCE(r.customer_consented_at, CURRENT_TIMESTAMP) ELSE NULL END,
                delivered_at = CASE WHEN :delivered THEN COALESCE(r.delivered_at, CURRENT_TIMESTAMP) ELSE NULL END,
                delivery_note = :deliveryNote,
                selection_revision_id = $revisionValue,
                version = r.version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE r.id = :roundId AND r.version = :expectedVersion
              $revisionPredicate
            """.trimIndent(),
        )
            .param("consented", consented)
            .param("delivered", delivered)
            .param("deliveryNote", deliveryNote)
            .param("roundId", roundId)
            .param("expectedVersion", expectedVersion)
        if (selectionRevisionId != null) {
            statement = statement.param("selectionRevisionId", selectionRevisionId)
        }
        val updated = statement.update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    fun setStudioRetouchCapability(studioId: Long, capability: String, enabled: Boolean, expectedVersion: Long) {
        requireVersion("studios", studioId, expectedVersion)
        jdbcClient.sql(
            """
            INSERT INTO studio_retouch_capabilities
                (studio_id, capability, enabled, created_at, updated_at)
            VALUES (:studioId, :capability, :enabled, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (studio_id, capability)
            DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        )
            .param("studioId", studioId)
            .param("capability", capability)
            .param("enabled", enabled)
            .update()
        bumpVersion("studios", studioId, expectedVersion)
    }

    private fun validateAlbumTemplateLayout(layout: Map<String, Any?>) {
        try {
            AdminAlbumTemplateLayout.parse(layout)
        } catch (_: InvalidAlbumTemplateLayoutException) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
    }

    private fun sharedPhotoExistsSql(sessionExpression: String, photoExpression: String): String = """
        EXISTS (
            SELECT 1
            FROM collab_sessions shared_session
            JOIN concept_folders shared_concept
              ON shared_concept.id = shared_session.concept_folder_id
             AND shared_concept.deleted_at IS NULL
            JOIN detail_folders shared_detail
              ON shared_detail.concept_folder_id = shared_concept.id
             AND shared_detail.deleted_at IS NULL
            JOIN photo_category_assignments shared_assignment
              ON shared_assignment.detail_folder_id = shared_detail.id
            WHERE shared_session.id = $sessionExpression
              AND shared_assignment.photo_id = $photoExpression
        )
    """.trimIndent()

    private fun requireVersion(table: String, id: Long, expectedVersion: Long, lock: Boolean = false) {
        val lockClause = if (lock) " FOR UPDATE" else ""
        val idColumn = if (table == "studios") "workspace_id" else "id"
        val current = jdbcClient.sql("SELECT version FROM $table WHERE $idColumn = :id$lockClause")
            .param("id", id)
            .query { rs, _ -> rs.getLong("version") }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
        if (current != expectedVersion) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    private fun requireActive(table: String, id: Long) {
        val idColumn = if (table == "studios") "workspace_id" else "id"
        val found = jdbcClient.sql("SELECT COUNT(*) FROM $table WHERE $idColumn = :id AND deleted_at IS NULL")
            .param("id", id)
            .query { rs, _ -> rs.getLong(1) }
            .single()
        if (found != 1L) throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
    }

    private fun lockGalleryScope(galleryId: Long, expectedVersion: Long): GalleryScope {
        val gallery = jdbcClient.sql(
            """
            SELECT g.version, g.workspace_id
            FROM galleries g
            JOIN workspaces w ON w.id = g.workspace_id AND w.deleted_at IS NULL
            LEFT JOIN studios s ON s.workspace_id = g.workspace_id
            WHERE g.id = :galleryId AND g.deleted_at IS NULL
              AND (w.type = 'PERSONAL' OR (s.deleted_at IS NULL AND s.suspended_at IS NULL))
            FOR UPDATE OF g, w
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .query { rs, _ ->
                GalleryScope(
                    version = rs.getLong("version"),
                    studioId = rs.getLong("workspace_id"),
                )
            }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
        if (gallery.version != expectedVersion) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        return gallery
    }

    private fun bumpVersion(table: String, id: Long, expectedVersion: Long, idColumn: String? = null) {
        val resolvedIdColumn = idColumn ?: if (table == "studios") "workspace_id" else "id"
        val updated = jdbcClient.sql(
            "UPDATE $table SET version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE $resolvedIdColumn = :id AND version = :expectedVersion",
        )
            .param("id", id)
            .param("expectedVersion", expectedVersion)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    /** 오케스트레이터 외 내부 호출도 동일한 앨범 저장 계약을 우회하지 못하게 한다. */
    private fun validateAlbumLayoutContract(folders: List<AlbumFolder>) {
        if (folders.size > MAX_ALBUM_FOLDERS || folders.sumOf { it.items.size } > MAX_ALBUM_ITEMS) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val photoIds = mutableSetOf<Long>()
        folders.forEach { folder ->
            if (folder.name.isBlank() || folder.name.length > MAX_ALBUM_FOLDER_NAME_LENGTH) {
                throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            }
            val sortOrders = mutableSetOf<Int>()
            folder.items.forEach { item ->
                if (
                    item.photoId <= 0 || item.sortOrder < 0 ||
                    !photoIds.add(item.photoId) || !sortOrders.add(item.sortOrder)
                ) {
                    throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
                }
                item.crop?.let(::validateNormalizedCrop)
            }
        }
    }

    private fun validateNormalizedCrop(crop: Map<String, Any?>) {
        if (crop.keys != NORMALIZED_CROP_KEYS) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val values = NORMALIZED_CROP_KEYS_IN_ORDER.map { name ->
            (crop[name] as? Number)?.toDouble()?.takeIf { candidate -> candidate.isFinite() }
                ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val (x, y, width, height) = values
        if (
            x < 0 || y < 0 || width <= 0 || height <= 0 ||
            x + width > 1 + NORMALIZED_CROP_EPSILON ||
            y + height > 1 + NORMALIZED_CROP_EPSILON
        ) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
    }

    data class WorkflowReservation(val requestHash: String?, val status: String?, val resultPayload: String?)
    data class StudioOwnerResult(
        val previousOwnerId: Long,
        val ownerId: Long,
    )
    data class StudioMemberResult(val memberId: Long)
    data class GalleryMemberResult(val memberId: Long)
    data class PhotoReplacementResult(
        val revisionId: Long,
        val revisionNumber: Long,
        val newStorageKey: String,
        val galleryId: Long,
        val jobIds: List<Long>,
    )
    data class PendingReplacementUpload(val storageKey: String, val contentType: String)
    data class SelectionRevisionResult(
        val revisionId: Long,
        val photoIds: List<Long>,
        val mockJobId: Long? = null,
        val previousRevisionId: Long? = null,
    )
    data class AiSelectionDraftResult(
        val jobId: Long,
        val status: String,
        val revisionId: Long?,
        val photoIds: List<Long>,
        val failureCode: String?,
    )
    data class SelectionItem(val photoId: Long, val retouchPhotoId: Long?, val sortOrder: Int)
    data class AlbumFolder(val name: String, val items: List<AlbumItem>)
    data class AlbumItem(val photoId: Long, val sortOrder: Int, val crop: Map<String, Any?>?)
    data class OperationRow(
        val id: Long,
        val action: String,
        val status: String,
        val targetType: String?,
        val targetId: String?,
        val attemptCount: Int,
    )
    data class ProcessingJob(
        val id: Long,
        val jobType: String,
        val status: String,
        val targetType: AdminResourceType,
        val targetId: Long,
        val revisionId: Long?,
        val payload: Map<String, Any?>,
        val attemptCount: Int,
        val failureCode: String?,
    )

    enum class GalleryTransition(val publicStatus: String, val workflowStatus: String) {
        SUBMIT("CLOSED", "IN_PROGRESS"),
        COMPLETE("CLOSED", "COMPLETED"),
    }

    private data class PendingReplacement(
        val id: Long,
        val storageKey: String,
        val originalFileName: String,
        val contentType: String,
    )

    private data class AlbumScope(
        val albumOrGalleryId: Long,
        val studioId: Long,
    )

    private data class GalleryScope(
        val version: Long,
        val studioId: Long,
    )

    private data class PhotoCurrent(
        val galleryId: Long,
        val storageKey: String,
        val previewKey: String?,
        val originalFileName: String,
        val contentType: String,
    )

    private data class AiCandidate(
        val photoId: Long,
        val embedding: DoubleArray,
        val rating: Double,
        val pixels: Long,
        val technicalQualityScore: Double?,
        val quality: Double = 0.0,
    )

    companion object {
        private val TRACE_ID = Regex("^[a-f0-9]{16}$")
        private const val QUALITY_WEIGHT = 0.65
        private const val DIVERSITY_WEIGHT = 0.35
        private val PUBLIC_STATUSES = setOf("DRAFT", "OPEN", "CLOSED")
        private val WORKFLOW_STATUSES = setOf("DRAFT", "IN_PROGRESS", "COMPLETED", "ARCHIVED")
        private const val MAX_ALBUM_FOLDERS = 50
        private const val MAX_ALBUM_ITEMS = 1_000
        private const val MAX_ALBUM_FOLDER_NAME_LENGTH = 100
        private const val NORMALIZED_CROP_EPSILON = 0.000_001
        private val NORMALIZED_CROP_KEYS_IN_ORDER = listOf("x", "y", "width", "height")
        private val NORMALIZED_CROP_KEYS = NORMALIZED_CROP_KEYS_IN_ORDER.toSet()
    }
}
