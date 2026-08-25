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

    data class ResourceReference(val type: AdminResourceType, val id: Long)
}
