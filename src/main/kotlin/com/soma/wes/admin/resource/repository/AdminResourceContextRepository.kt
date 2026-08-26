package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.resource.domain.AdminResourceType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class AdminResourceContextRepository(
    private val jdbcClient: JdbcClient,
) {

    fun findRelations(type: AdminResourceType, id: Long): Set<ResourceReference> = when (type) {
        AdminResourceType.USER -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.STUDIO, "SELECT id FROM studios WHERE user_id = :id", id))
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM gallery_members WHERE user_id = :id", id))
        }
        AdminResourceType.STUDIO -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.USER, "SELECT user_id AS id FROM studios WHERE id = :id", id))
            addAll(references(AdminResourceType.GALLERY, "SELECT id FROM galleries WHERE studio_id = :id", id))
        }
        AdminResourceType.GALLERY -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.STUDIO, "SELECT studio_id AS id FROM galleries WHERE id = :id", id))
            addAll(references(AdminResourceType.USER, "SELECT user_id AS id FROM gallery_members WHERE gallery_id = :id", id))
            addAll(references(AdminResourceType.PHOTO, "SELECT id FROM photos WHERE gallery_id = :id", id))
            addAll(references(AdminResourceType.SELECTION, "SELECT id FROM photo_selections WHERE gallery_id = :id", id))
            addAll(references(AdminResourceType.COLLABORATION, "SELECT id FROM collab_sessions WHERE gallery_id = :id", id))
            addAll(references(AdminResourceType.ALBUM, "SELECT id FROM photo_folder_groups WHERE gallery_id = :id", id))
            addAll(references(AdminResourceType.RETOUCH_REQUEST, "SELECT id FROM retouch_rounds WHERE gallery_id = :id", id))
        }
        AdminResourceType.PHOTO -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM photos WHERE id = :id", id))
        }
        AdminResourceType.SELECTION -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM photo_selections WHERE id = :id", id))
            addAll(references(AdminResourceType.PHOTO, "SELECT photo_id AS id FROM photo_selection_items WHERE selection_id = :id", id))
        }
        AdminResourceType.COLLABORATION -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM collab_sessions WHERE id = :id", id))
            addAll(references(AdminResourceType.PHOTO, "SELECT photo_id AS id FROM collab_photos WHERE collab_session_id = :id", id))
        }
        AdminResourceType.ALBUM -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM photo_folder_groups WHERE id = :id", id))
            addAll(references(AdminResourceType.PHOTO, "SELECT photo_id AS id FROM photo_folder_items WHERE group_id = :id", id))
        }
        AdminResourceType.RETOUCH_REQUEST -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM retouch_rounds WHERE id = :id", id))
            addAll(references(AdminResourceType.PHOTO, "SELECT photo_id AS id FROM retouch_photos WHERE round_id = :id", id))
        }
    }

    fun findFacts(type: AdminResourceType, id: Long): Map<String, Any?> = when (type) {
        AdminResourceType.USER -> singleFacts(
            """
                SELECT
                    (SELECT COUNT(*) FROM refresh_tokens WHERE user_id = :id) AS active_sessions,
                    (SELECT COUNT(*) FROM studios WHERE user_id = :id) AS owned_studios,
                    (SELECT COUNT(*) FROM gallery_members WHERE user_id = :id) AS joined_galleries
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "activeSessions" to rs.getLong("active_sessions"),
            "ownedStudios" to rs.getLong("owned_studios"),
            "joinedGalleries" to rs.getLong("joined_galleries"),
        ) }
        AdminResourceType.STUDIO -> singleFacts(
            """
                SELECT
                    (SELECT COUNT(*) FROM galleries WHERE studio_id = :id) AS galleries,
                    (SELECT COUNT(*) FROM photos p JOIN galleries g ON g.id = p.gallery_id WHERE g.studio_id = :id) AS photos,
                    (SELECT COALESCE(SUM(p.byte_size), 0) FROM photos p JOIN galleries g ON g.id = p.gallery_id WHERE g.studio_id = :id) AS storage_bytes
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "galleries" to rs.getLong("galleries"),
            "photos" to rs.getLong("photos"),
            "storageBytes" to rs.getLong("storage_bytes"),
        ) }
        AdminResourceType.GALLERY -> singleFacts(
            """
                SELECT
                    (SELECT COUNT(*) FROM gallery_members WHERE gallery_id = :id) AS members,
                    (SELECT COUNT(*) FROM photos WHERE gallery_id = :id) AS photos,
                    (SELECT COUNT(*) FROM photo_selection_items i JOIN photo_selections s ON s.id = i.selection_id WHERE s.gallery_id = :id) AS selected_photos,
                    (SELECT CASE WHEN revoked_at IS NOT NULL THEN 'REVOKED' WHEN expires_at < CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE 'ACTIVE' END
                       FROM gallery_invites WHERE gallery_id = :id ORDER BY id DESC LIMIT 1) AS invite_status,
                    (SELECT expires_at FROM gallery_invites WHERE gallery_id = :id ORDER BY id DESC LIMIT 1) AS invite_expires_at
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "members" to rs.getLong("members"),
            "photos" to rs.getLong("photos"),
            "selectedPhotos" to rs.getLong("selected_photos"),
            "inviteStatus" to rs.getString("invite_status"),
            "inviteExpiresAt" to rs.getObject("invite_expires_at"),
        ) }
        AdminResourceType.PHOTO -> singleFacts(
            """
                SELECT byte_size, width, height, camera_make, camera_model, taken_at,
                       embedding IS NOT NULL AS analyzed,
                       (SELECT COUNT(*) FROM photo_folder_items WHERE photo_id = :id) AS album_references,
                       (SELECT COUNT(*) FROM photo_selection_items WHERE photo_id = :id) AS selection_references,
                       (SELECT COUNT(*) FROM retouch_photos WHERE photo_id = :id) AS retouch_references
                FROM photos WHERE id = :id
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "byteSize" to rs.getObject("byte_size"),
            "width" to rs.getObject("width"),
            "height" to rs.getObject("height"),
            "cameraMake" to rs.getString("camera_make"),
            "cameraModel" to rs.getString("camera_model"),
            "takenAt" to rs.getObject("taken_at"),
            "analyzed" to rs.getBoolean("analyzed"),
            "albumReferences" to rs.getLong("album_references"),
            "selectionReferences" to rs.getLong("selection_references"),
            "retouchReferences" to rs.getLong("retouch_references"),
        ) }
        AdminResourceType.SELECTION -> singleFacts(
            """
                SELECT COUNT(*) AS selected_photos,
                       COUNT(*) FILTER (WHERE i.retouch_photo_id IS NOT NULL) AS retouched_photos
                FROM photo_selection_items i WHERE i.selection_id = :id
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "selectedPhotos" to rs.getLong("selected_photos"),
            "retouchedPhotos" to rs.getLong("retouched_photos"),
        ) }
        AdminResourceType.COLLABORATION -> singleFacts(
            """
                SELECT
                    (SELECT COUNT(*) FROM collab_guests WHERE collab_session_id = :id) AS guests,
                    (SELECT COUNT(*) FROM collab_photos WHERE collab_session_id = :id) AS photos,
                    (SELECT COUNT(*) FROM collab_photo_comments c JOIN collab_photos p ON p.id = c.collab_photo_id WHERE p.collab_session_id = :id) AS comments,
                    (SELECT COUNT(*) FROM collab_photo_likes l JOIN collab_photos p ON p.id = l.collab_photo_id WHERE p.collab_session_id = :id) AS likes
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "guests" to rs.getLong("guests"),
            "photos" to rs.getLong("photos"),
            "comments" to rs.getLong("comments"),
            "likes" to rs.getLong("likes"),
        ) }
        AdminResourceType.ALBUM -> singleFacts(
            """
                SELECT
                    (SELECT COUNT(*) FROM photo_folders WHERE group_id = :id) AS folders,
                    (SELECT COUNT(*) FROM photo_folder_items WHERE group_id = :id) AS photos
            """.trimIndent(), id,
        ) { rs -> linkedMapOf("folders" to rs.getLong("folders"), "photos" to rs.getLong("photos")) }
        AdminResourceType.RETOUCH_REQUEST -> singleFacts(
            """
                SELECT COUNT(*) AS photos,
                       COUNT(*) FILTER (WHERE result_key IS NOT NULL) AS completed_results,
                       COUNT(*) FILTER (WHERE result_key IS NULL) AS pending_results
                FROM retouch_photos WHERE round_id = :id
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "photos" to rs.getLong("photos"),
            "completedResults" to rs.getLong("completed_results"),
            "pendingResults" to rs.getLong("pending_results"),
        ) }
    }

    fun findSections(type: AdminResourceType, id: Long): Map<String, List<Map<String, Any?>>> = when (type) {
        AdminResourceType.USER -> linkedMapOf(
            "sessions" to rows(
                """SELECT user_id, expires_at, created_at, updated_at FROM refresh_tokens WHERE user_id = :id""",
                id,
            ) { rs -> linkedMapOf(
                "userId" to rs.getLong("user_id"),
                "expiresAt" to rs.getObject("expires_at"),
                "createdAt" to rs.getObject("created_at"),
                "updatedAt" to rs.getObject("updated_at"),
            ) },
            "galleryMemberships" to rows(
                """
                    SELECT m.id AS member_id, m.gallery_id, g.title, g.status, m.created_at
                    FROM gallery_members m JOIN galleries g ON g.id = m.gallery_id
                    WHERE m.user_id = :id ORDER BY m.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "memberId" to rs.getLong("member_id"),
                "galleryId" to rs.getLong("gallery_id"),
                "galleryTitle" to rs.getString("title"),
                "galleryStatus" to rs.getString("status"),
                "joinedAt" to rs.getObject("created_at"),
            ) },
        )
        AdminResourceType.STUDIO -> linkedMapOf(
            "galleries" to rows(
                """
                    SELECT id, title, status, selection_deadline, deleted_at, created_at
                    FROM galleries WHERE studio_id = :id ORDER BY id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "title" to rs.getString("title"),
                "status" to rs.getString("status"),
                "selectionDeadline" to rs.getObject("selection_deadline"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "createdAt" to rs.getObject("created_at"),
            ) },
        )
        AdminResourceType.GALLERY -> linkedMapOf(
            "members" to rows(
                """
                    SELECT m.id AS member_id, u.id AS user_id, u.nickname, u.email, m.created_at
                    FROM gallery_members m JOIN users u ON u.id = m.user_id
                    WHERE m.gallery_id = :id ORDER BY m.id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "memberId" to rs.getLong("member_id"),
                "userId" to rs.getLong("user_id"),
                "nickname" to rs.getString("nickname"),
                "email" to rs.getString("email"),
                "joinedAt" to rs.getObject("created_at"),
            ) },
            "invites" to rows(
                """
                    SELECT id, expires_at, revoked_at, created_at,
                           CASE WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                                WHEN expires_at < CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE 'ACTIVE' END AS status
                    FROM gallery_invites WHERE gallery_id = :id ORDER BY id DESC LIMIT 20
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "status" to rs.getString("status"),
                "expiresAt" to rs.getObject("expires_at"),
                "revokedAt" to rs.getObject("revoked_at"),
                "createdAt" to rs.getObject("created_at"),
                "token" to "[MASKED]",
            ) },
            "selections" to rows(
                "SELECT id, status, submitted_at, created_at FROM photo_selections WHERE gallery_id = :id ORDER BY id DESC",
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "status" to rs.getString("status"),
                "submittedAt" to rs.getObject("submitted_at"),
                "createdAt" to rs.getObject("created_at"),
            ) },
            "collaborationLinks" to rows(
                """
                    SELECT id, name, revoked_at, created_at FROM collab_sessions
                    WHERE gallery_id = :id ORDER BY id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "name" to rs.getString("name"),
                "status" to if (rs.getObject("revoked_at") == null) "ACTIVE" else "REVOKED",
                "token" to "[MASKED]",
                "createdAt" to rs.getObject("created_at"),
            ) },
            "albums" to rows(
                "SELECT id, name, created_at FROM photo_folder_groups WHERE gallery_id = :id ORDER BY id DESC LIMIT 100",
                id,
            ) { rs -> linkedMapOf("id" to rs.getLong("id"), "name" to rs.getString("name"), "createdAt" to rs.getObject("created_at")) },
            "retouchRounds" to rows(
                """
                    SELECT id, round_no, status, requested_at, completed_at
                    FROM retouch_rounds WHERE gallery_id = :id ORDER BY round_no DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "roundNo" to rs.getInt("round_no"),
                "status" to rs.getString("status"),
                "requestedAt" to rs.getObject("requested_at"),
                "completedAt" to rs.getObject("completed_at"),
            ) },
            "processingJobs" to rows(
                """
                    SELECT id, action, status, failure_code, result_payload, correlation_id,
                           attempt_count, created_at, updated_at
                    FROM admin_idempotency_keys
                    WHERE target_type = 'GALLERY' AND target_id = CAST(:id AS TEXT)
                    ORDER BY id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "action" to rs.getString("action"),
                "status" to rs.getString("status"),
                "failureCode" to rs.getString("failure_code"),
                "result" to rs.getString("result_payload"),
                "correlationId" to rs.getString("correlation_id"),
                "attemptCount" to rs.getInt("attempt_count"),
                "createdAt" to rs.getObject("created_at"),
                "lastRunAt" to rs.getObject("updated_at"),
            ) },
        )
        AdminResourceType.PHOTO -> linkedMapOf(
            "selectionReferences" to rows(
                """
                    SELECT i.id AS item_id, i.selection_id, s.status, i.retouch_photo_id
                    FROM photo_selection_items i JOIN photo_selections s ON s.id = i.selection_id
                    WHERE i.photo_id = :id ORDER BY i.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "itemId" to rs.getLong("item_id"),
                "selectionId" to rs.getLong("selection_id"),
                "selectionStatus" to rs.getString("status"),
                "retouchPhotoId" to rs.getObject("retouch_photo_id"),
            ) },
            "albumReferences" to rows(
                """
                    SELECT i.id AS item_id, i.group_id, i.folder_id, f.name AS folder_name
                    FROM photo_folder_items i JOIN photo_folders f ON f.id = i.folder_id
                    WHERE i.photo_id = :id ORDER BY i.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "itemId" to rs.getLong("item_id"),
                "albumId" to rs.getLong("group_id"),
                "folderId" to rs.getLong("folder_id"),
                "folderName" to rs.getString("folder_name"),
            ) },
            "retouchReferences" to rows(
                """
                    SELECT id, round_id, request_text, annotation_key IS NOT NULL AS annotated,
                           result_key IS NOT NULL AS result_ready
                    FROM retouch_photos WHERE photo_id = :id ORDER BY id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "roundId" to rs.getLong("round_id"),
                "requestText" to rs.getString("request_text"),
                "annotated" to rs.getBoolean("annotated"),
                "resultReady" to rs.getBoolean("result_ready"),
            ) },
        )
        AdminResourceType.SELECTION -> linkedMapOf(
            "items" to rows(
                """
                    SELECT i.id, i.photo_id, p.original_file_name, i.retouch_photo_id, i.created_at
                    FROM photo_selection_items i JOIN photos p ON p.id = i.photo_id
                    WHERE i.selection_id = :id ORDER BY i.id LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "photoId" to rs.getLong("photo_id"),
                "fileName" to rs.getString("original_file_name"),
                "retouchPhotoId" to rs.getObject("retouch_photo_id"),
                "selectedAt" to rs.getObject("created_at"),
            ) },
        )
        AdminResourceType.COLLABORATION -> linkedMapOf(
            "guests" to rows(
                "SELECT id, nickname, created_at FROM collab_guests WHERE collab_session_id = :id ORDER BY id LIMIT 100",
                id,
            ) { rs -> linkedMapOf("id" to rs.getLong("id"), "nickname" to rs.getString("nickname"), "createdAt" to rs.getObject("created_at")) },
            "comments" to rows(
                """
                    SELECT c.id, p.photo_id, g.id AS guest_id, g.nickname, c.content, c.created_at
                    FROM collab_photo_comments c
                    JOIN collab_photos p ON p.id = c.collab_photo_id
                    JOIN collab_guests g ON g.id = c.collab_guest_id
                    WHERE p.collab_session_id = :id ORDER BY c.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "photoId" to rs.getLong("photo_id"),
                "guestId" to rs.getLong("guest_id"),
                "nickname" to rs.getString("nickname"),
                "content" to rs.getString("content"),
                "createdAt" to rs.getObject("created_at"),
            ) },
            "likes" to rows(
                """
                    SELECT l.id, p.photo_id, g.id AS guest_id, g.nickname, l.created_at
                    FROM collab_photo_likes l
                    JOIN collab_photos p ON p.id = l.collab_photo_id
                    JOIN collab_guests g ON g.id = l.collab_guest_id
                    WHERE p.collab_session_id = :id ORDER BY l.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "photoId" to rs.getLong("photo_id"),
                "guestId" to rs.getLong("guest_id"),
                "nickname" to rs.getString("nickname"),
                "createdAt" to rs.getObject("created_at"),
            ) },
        )
        AdminResourceType.ALBUM -> linkedMapOf(
            "folders" to rows(
                "SELECT id, name, created_at FROM photo_folders WHERE group_id = :id ORDER BY id LIMIT 100",
                id,
            ) { rs -> linkedMapOf("id" to rs.getLong("id"), "name" to rs.getString("name"), "createdAt" to rs.getObject("created_at")) },
            "items" to rows(
                """
                    SELECT i.id, i.folder_id, f.name AS folder_name, i.photo_id, p.original_file_name
                    FROM photo_folder_items i
                    JOIN photo_folders f ON f.id = i.folder_id
                    JOIN photos p ON p.id = i.photo_id
                    WHERE i.group_id = :id ORDER BY i.id LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "folderId" to rs.getLong("folder_id"),
                "folderName" to rs.getString("folder_name"),
                "photoId" to rs.getLong("photo_id"),
                "fileName" to rs.getString("original_file_name"),
            ) },
        )
        AdminResourceType.RETOUCH_REQUEST -> linkedMapOf(
            "items" to rows(
                """
                    SELECT r.id, r.photo_id, p.original_file_name, r.request_text,
                           r.annotation_key IS NOT NULL AS annotated,
                           r.result_key IS NOT NULL AS result_ready, r.result_content_type
                    FROM retouch_photos r JOIN photos p ON p.id = r.photo_id
                    WHERE r.round_id = :id ORDER BY r.id LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "photoId" to rs.getLong("photo_id"),
                "fileName" to rs.getString("original_file_name"),
                "requestText" to rs.getString("request_text"),
                "annotated" to rs.getBoolean("annotated"),
                "resultReady" to rs.getBoolean("result_ready"),
                "resultContentType" to rs.getString("result_content_type"),
            ) },
        )
    }.filterValues { it.isNotEmpty() }

    private fun references(type: AdminResourceType, sql: String, id: Long): List<ResourceReference> =
        jdbcClient.sql(sql)
            .param("id", id)
            .query { rs, _ -> ResourceReference(type, rs.getLong("id")) }
            .list()

    private fun singleFacts(
        sql: String,
        id: Long,
        mapper: (ResultSet) -> Map<String, Any?>,
    ): Map<String, Any?> = jdbcClient.sql(sql)
        .param("id", id)
        .query { rs, _ -> mapper(rs) }
        .optional()
        .orElse(emptyMap())

    private fun rows(
        sql: String,
        id: Long,
        mapper: (ResultSet) -> Map<String, Any?>,
    ): List<Map<String, Any?>> = jdbcClient.sql(sql)
        .param("id", id)
        .query { rs, _ -> mapper(rs) }
        .list()

    data class ResourceReference(val type: AdminResourceType, val id: Long)
}
