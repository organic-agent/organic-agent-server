package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.dto.AdminRetouchArtifactType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.ZonedDateTime

/**
 * 보정 표식·결과 파일의 관리자용 2단계 업로드 저장소다.
 *
 * storage key는 이 저장소와 스토리지 서비스 사이에서만 흐른다. 컨텍스트·감사 스냅샷과
 * 영구 멱등 응답은 upload id, 유형, 버전만 기록한다.
 */
@Repository
class AdminRetouchArtifactRepository(
    private val jdbcClient: JdbcClient,
) {

    fun scope(roundId: Long, retouchPhotoId: Long): RetouchArtifactScope = jdbcClient.sql(
        """
        SELECT r.gallery_id, r.round_no
        FROM retouch_rounds r
        JOIN retouch_photos p ON p.round_id = r.id
        WHERE r.id = :roundId AND p.id = :retouchPhotoId
          AND r.deleted_at IS NULL AND p.deleted_at IS NULL
        """.trimIndent(),
    )
        .param("roundId", roundId)
        .param("retouchPhotoId", retouchPhotoId)
        .query { rs, _ -> RetouchArtifactScope(
            galleryId = rs.getLong("gallery_id"),
            roundNo = rs.getInt("round_no"),
        ) }
        .optional()
        .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }

    fun createUpload(
        roundId: Long,
        retouchPhotoId: Long,
        artifactType: AdminRetouchArtifactType,
        storageKey: String,
        originalFileName: String,
        contentType: String,
        expiresAt: ZonedDateTime,
        actorAdminId: Long,
        reason: String,
        expectedRoundVersion: Long,
        expectedPhotoVersion: Long,
    ): RetouchArtifactUploadCreated {
        lockRound(roundId, expectedRoundVersion, artifactType.requiredRoundStatus)
        lockItem(roundId, retouchPhotoId, expectedPhotoVersion)
        jdbcClient.sql(
            """
            UPDATE admin_retouch_artifact_uploads
            SET status = 'EXPIRED'
            WHERE round_id = :roundId AND retouch_photo_id = :retouchPhotoId
              AND artifact_type = :artifactType AND status = 'PENDING'
              AND expires_at <= CURRENT_TIMESTAMP
            """.trimIndent(),
        )
            .param("roundId", roundId)
            .param("retouchPhotoId", retouchPhotoId)
            .param("artifactType", artifactType.name)
            .update()
        val activePending = jdbcClient.sql(
            """
            SELECT COUNT(*)
            FROM admin_retouch_artifact_uploads
            WHERE round_id = :roundId AND retouch_photo_id = :retouchPhotoId
              AND artifact_type = :artifactType AND status = 'PENDING'
            """.trimIndent(),
        )
            .param("roundId", roundId)
            .param("retouchPhotoId", retouchPhotoId)
            .param("artifactType", artifactType.name)
            .query { rs, _ -> rs.getLong(1) }
            .single()
        if (activePending != 0L) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)

        val uploadId = jdbcClient.sql(
            """
            INSERT INTO admin_retouch_artifact_uploads
                (round_id, retouch_photo_id, artifact_type, storage_key, original_file_name,
                 content_type, status, expires_at, actor_admin_id, reason, created_at)
            VALUES
                (:roundId, :retouchPhotoId, :artifactType, :storageKey, :originalFileName,
                 :contentType, 'PENDING', :expiresAt, :actorAdminId, :reason, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("roundId", roundId)
            .param("retouchPhotoId", retouchPhotoId)
            .param("artifactType", artifactType.name)
            .param("storageKey", storageKey)
            .param("originalFileName", originalFileName)
            .param("contentType", contentType)
            .param("expiresAt", expiresAt.toOffsetDateTime())
            .param("actorAdminId", actorAdminId)
            .param("reason", reason)
            .query { rs, _ -> rs.getLong("id") }
            .single()
        bumpVersions(roundId, retouchPhotoId, expectedRoundVersion, expectedPhotoVersion)
        return RetouchArtifactUploadCreated(
            uploadId = uploadId,
            roundVersion = expectedRoundVersion + 1,
            retouchPhotoVersion = expectedPhotoVersion + 1,
        )
    }

    fun pendingUpload(
        roundId: Long,
        retouchPhotoId: Long,
        uploadId: Long,
    ): PendingRetouchArtifactUpload = jdbcClient.sql(
        """
        SELECT storage_key, content_type
        FROM admin_retouch_artifact_uploads
        WHERE id = :uploadId AND round_id = :roundId AND retouch_photo_id = :retouchPhotoId
          AND status = 'PENDING' AND expires_at > CURRENT_TIMESTAMP
        """.trimIndent(),
    )
        .param("uploadId", uploadId)
        .param("roundId", roundId)
        .param("retouchPhotoId", retouchPhotoId)
        .query { rs, _ -> PendingRetouchArtifactUpload(
            storageKey = rs.getString("storage_key"),
            contentType = rs.getString("content_type"),
        ) }
        .optional()
        .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }

    fun completeUpload(
        roundId: Long,
        retouchPhotoId: Long,
        uploadId: Long,
        expectedRoundVersion: Long,
        expectedPhotoVersion: Long,
    ): RetouchArtifactUploadCompleted {
        // 유형은 불변 컬럼이라 잠금 전에 읽고, 도메인 행을 round -> item 순서로 잠근 뒤
        // pending 행을 최종 재검증한다. issue/update와 잠금 순서를 맞춰 교착을 피한다.
        val expectedArtifactType = jdbcClient.sql(
            """
            SELECT artifact_type
            FROM admin_retouch_artifact_uploads
            WHERE id = :uploadId AND round_id = :roundId AND retouch_photo_id = :retouchPhotoId
              AND status = 'PENDING' AND expires_at > CURRENT_TIMESTAMP
            """.trimIndent(),
        )
            .param("uploadId", uploadId)
            .param("roundId", roundId)
            .param("retouchPhotoId", retouchPhotoId)
            .query { rs, _ -> AdminRetouchArtifactType.valueOf(rs.getString("artifact_type")) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
        lockRound(roundId, expectedRoundVersion, expectedArtifactType.requiredRoundStatus)
        lockItem(roundId, retouchPhotoId, expectedPhotoVersion)
        val pending = jdbcClient.sql(
            """
            SELECT artifact_type, storage_key, content_type
            FROM admin_retouch_artifact_uploads
            WHERE id = :uploadId AND round_id = :roundId AND retouch_photo_id = :retouchPhotoId
              AND status = 'PENDING' AND expires_at > CURRENT_TIMESTAMP
            FOR UPDATE
            """.trimIndent(),
        )
            .param("uploadId", uploadId)
            .param("roundId", roundId)
            .param("retouchPhotoId", retouchPhotoId)
            .query { rs, _ -> CompletingRetouchArtifactUpload(
                artifactType = AdminRetouchArtifactType.valueOf(rs.getString("artifact_type")),
                storageKey = rs.getString("storage_key"),
                contentType = rs.getString("content_type"),
            ) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
        if (pending.artifactType != expectedArtifactType) {
            throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        val assignments = when (pending.artifactType) {
            AdminRetouchArtifactType.ANNOTATION -> "annotation_key = :storageKey"
            AdminRetouchArtifactType.RESULT ->
                "result_key = :storageKey, result_content_type = :contentType"
        }
        val itemUpdated = jdbcClient.sql(
            """
            UPDATE retouch_photos
            SET $assignments, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :retouchPhotoId AND round_id = :roundId
              AND version = :expectedPhotoVersion AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("storageKey", pending.storageKey)
            .param("contentType", pending.contentType)
            .param("retouchPhotoId", retouchPhotoId)
            .param("roundId", roundId)
            .param("expectedPhotoVersion", expectedPhotoVersion)
            .update()
        if (itemUpdated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        val roundUpdated = jdbcClient.sql(
            """
            UPDATE retouch_rounds
            SET version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :roundId AND version = :expectedRoundVersion AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("roundId", roundId)
            .param("expectedRoundVersion", expectedRoundVersion)
            .update()
        if (roundUpdated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        val uploadUpdated = jdbcClient.sql(
            """
            UPDATE admin_retouch_artifact_uploads
            SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
            WHERE id = :uploadId AND status = 'PENDING'
            """.trimIndent(),
        )
            .param("uploadId", uploadId)
            .update()
        if (uploadUpdated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        return RetouchArtifactUploadCompleted(
            uploadId = uploadId,
            artifactType = pending.artifactType,
            contentType = pending.contentType,
            roundVersion = expectedRoundVersion + 1,
            retouchPhotoVersion = expectedPhotoVersion + 1,
        )
    }

    fun access(
        roundId: Long,
        retouchPhotoId: Long,
        artifactType: AdminRetouchArtifactType,
    ): RetouchArtifactAccess = jdbcClient.sql(
        """
        SELECT
            CASE WHEN :artifactType = 'ANNOTATION' THEN p.annotation_key ELSE p.result_key END AS storage_key,
            CASE WHEN :artifactType = 'ANNOTATION' THEN 'image/png' ELSE p.result_content_type END AS content_type,
            upload.original_file_name
        FROM retouch_photos p
        JOIN retouch_rounds r ON r.id = p.round_id
        LEFT JOIN LATERAL (
            SELECT u.original_file_name
            FROM admin_retouch_artifact_uploads u
            WHERE u.round_id = r.id AND u.retouch_photo_id = p.id
              AND u.artifact_type = :artifactType AND u.status = 'COMPLETED'
              AND u.storage_key = CASE
                    WHEN :artifactType = 'ANNOTATION' THEN p.annotation_key
                    ELSE p.result_key
                  END
            ORDER BY u.completed_at DESC, u.id DESC
            LIMIT 1
        ) upload ON TRUE
        WHERE r.id = :roundId AND p.id = :retouchPhotoId
          AND r.deleted_at IS NULL AND p.deleted_at IS NULL
          AND CASE
                WHEN :artifactType = 'ANNOTATION' THEN p.annotation_key
                ELSE p.result_key
              END IS NOT NULL
        """.trimIndent(),
    )
        .param("artifactType", artifactType.name)
        .param("roundId", roundId)
        .param("retouchPhotoId", retouchPhotoId)
        .query { rs, _ -> RetouchArtifactAccess(
            storageKey = rs.getString("storage_key"),
            originalFileName = rs.getString("original_file_name")
                ?: fallbackFileName(retouchPhotoId, artifactType, rs.getString("content_type")),
            contentType = rs.getString("content_type"),
        ) }
        .optional()
        .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }

    private fun lockRound(roundId: Long, expectedVersion: Long, requiredStatus: String) {
        val lockedId = jdbcClient.sql(
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
        if (lockedId == null) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    private fun lockItem(roundId: Long, retouchPhotoId: Long, expectedVersion: Long) {
        val lockedId = jdbcClient.sql(
            """
            SELECT id FROM retouch_photos
            WHERE id = :retouchPhotoId AND round_id = :roundId
              AND version = :expectedVersion AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
        )
            .param("retouchPhotoId", retouchPhotoId)
            .param("roundId", roundId)
            .param("expectedVersion", expectedVersion)
            .query { rs, _ -> rs.getLong("id") }
            .optional()
            .orElse(null)
        if (lockedId == null) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    private fun bumpVersions(
        roundId: Long,
        retouchPhotoId: Long,
        expectedRoundVersion: Long,
        expectedPhotoVersion: Long,
    ) {
        val itemUpdated = jdbcClient.sql(
            """
            UPDATE retouch_photos
            SET version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :retouchPhotoId AND round_id = :roundId
              AND version = :expectedPhotoVersion AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("retouchPhotoId", retouchPhotoId)
            .param("roundId", roundId)
            .param("expectedPhotoVersion", expectedPhotoVersion)
            .update()
        if (itemUpdated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        val roundUpdated = jdbcClient.sql(
            """
            UPDATE retouch_rounds
            SET version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :roundId AND version = :expectedRoundVersion AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("roundId", roundId)
            .param("expectedRoundVersion", expectedRoundVersion)
            .update()
        if (roundUpdated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
    }

    private fun fallbackFileName(
        retouchPhotoId: Long,
        artifactType: AdminRetouchArtifactType,
        contentType: String,
    ): String {
        val extension = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> "bin"
        }
        return "retouch-${retouchPhotoId}-${artifactType.name.lowercase()}.$extension"
    }

    data class RetouchArtifactScope(val galleryId: Long, val roundNo: Int)
    data class RetouchArtifactUploadCreated(
        val uploadId: Long,
        val roundVersion: Long,
        val retouchPhotoVersion: Long,
    )

    private val AdminRetouchArtifactType.requiredRoundStatus: String
        get() = when (this) {
            AdminRetouchArtifactType.ANNOTATION -> "DRAFTING"
            AdminRetouchArtifactType.RESULT -> "REQUESTED"
        }
    data class PendingRetouchArtifactUpload(val storageKey: String, val contentType: String)
    data class RetouchArtifactUploadCompleted(
        val uploadId: Long,
        val artifactType: AdminRetouchArtifactType,
        val contentType: String,
        val roundVersion: Long,
        val retouchPhotoVersion: Long,
    )
    data class RetouchArtifactAccess(
        val storageKey: String,
        val originalFileName: String,
        val contentType: String,
    )
    private data class CompletingRetouchArtifactUpload(
        val artifactType: AdminRetouchArtifactType,
        val storageKey: String,
        val contentType: String,
    )
}
