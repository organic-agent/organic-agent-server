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
            addAll(references(AdminResourceType.WORKSPACE, "SELECT workspace_id AS id FROM workspace_members WHERE user_id = :id AND deleted_at IS NULL", id))
            addAll(references(AdminResourceType.STUDIO, "SELECT wm.workspace_id AS id FROM workspace_members wm JOIN studios s ON s.workspace_id = wm.workspace_id WHERE wm.user_id = :id AND wm.deleted_at IS NULL", id))
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM gallery_members WHERE user_id = :id AND deleted_at IS NULL", id))
        }
        AdminResourceType.WORKSPACE -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.USER, "SELECT user_id AS id FROM workspace_members WHERE workspace_id = :id AND deleted_at IS NULL", id))
            addAll(references(AdminResourceType.STUDIO, "SELECT workspace_id AS id FROM studios WHERE workspace_id = :id", id))
            addAll(references(AdminResourceType.GALLERY, "SELECT id FROM galleries WHERE workspace_id = :id", id))
        }
        AdminResourceType.STUDIO -> linkedSetOf<ResourceReference>().apply {
            add(ResourceReference(AdminResourceType.WORKSPACE, id))
            addAll(references(AdminResourceType.USER, "SELECT user_id AS id FROM workspace_members WHERE workspace_id = :id AND deleted_at IS NULL", id))
            addAll(references(AdminResourceType.GALLERY, "SELECT id FROM galleries WHERE workspace_id = :id", id))
        }
        AdminResourceType.GALLERY -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.WORKSPACE, "SELECT workspace_id AS id FROM galleries WHERE id = :id", id))
            addAll(references(AdminResourceType.STUDIO, "SELECT workspace_id AS id FROM galleries WHERE id = :id AND EXISTS (SELECT 1 FROM studios WHERE workspace_id = galleries.workspace_id)", id))
            addAll(references(AdminResourceType.USER, "SELECT user_id AS id FROM gallery_members WHERE gallery_id = :id AND deleted_at IS NULL", id))
            addAll(references(AdminResourceType.PHOTO, "SELECT id FROM photos WHERE gallery_id = :id", id))
            addAll(references(AdminResourceType.SELECTION, "SELECT id FROM photo_selections WHERE gallery_id = :id", id))
            addAll(references(AdminResourceType.COLLABORATION, "SELECT id FROM collab_sessions WHERE gallery_id = :id", id))
            addAll(references(AdminResourceType.RETOUCH_REQUEST, "SELECT id FROM retouch_rounds WHERE gallery_id = :id", id))
            addAll(references(AdminResourceType.CONCEPT_FOLDER, "SELECT id FROM concept_folders WHERE gallery_id = :id", id))
            addAll(references(AdminResourceType.CATEGORIZATION_JOB, "SELECT id FROM categorization_jobs WHERE gallery_id = :id", id))
        }
        AdminResourceType.PHOTO -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM photos WHERE id = :id", id))
            addAll(references(AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT, "SELECT photo_id AS id FROM photo_category_assignments WHERE photo_id = :id", id))
            addAll(references(AdminResourceType.PHOTO_RATING, "SELECT photo_id AS id FROM photo_ratings WHERE photo_id = :id", id))
            addAll(references(AdminResourceType.CATEGORIZATION_JOB, "SELECT job_id AS id FROM categorization_job_photos WHERE photo_id = :id", id))
        }
        AdminResourceType.CONCEPT_FOLDER -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM concept_folders WHERE id = :id", id))
            addAll(references(AdminResourceType.DETAIL_FOLDER, "SELECT id FROM detail_folders WHERE concept_folder_id = :id", id))
            addAll(references(AdminResourceType.COLLABORATION, "SELECT id FROM collab_sessions WHERE concept_folder_id = :id", id))
        }
        AdminResourceType.DETAIL_FOLDER -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.CONCEPT_FOLDER, "SELECT concept_folder_id AS id FROM detail_folders WHERE id = :id", id))
            addAll(references(AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT, "SELECT photo_id AS id FROM photo_category_assignments WHERE detail_folder_id = :id", id))
            addAll(references(AdminResourceType.PHOTO, "SELECT photo_id AS id FROM photo_category_assignments WHERE detail_folder_id = :id", id))
        }
        AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT -> linkedSetOf<ResourceReference>().apply {
            add(ResourceReference(AdminResourceType.PHOTO, id))
            addAll(references(AdminResourceType.DETAIL_FOLDER, "SELECT detail_folder_id AS id FROM photo_category_assignments WHERE photo_id = :id", id))
            addAll(references(AdminResourceType.USER, "SELECT assigned_by_user_id AS id FROM photo_category_assignments WHERE photo_id = :id AND assigned_by_user_id IS NOT NULL", id))
        }
        AdminResourceType.CATEGORIZATION_JOB -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM categorization_jobs WHERE id = :id", id))
            addAll(references(AdminResourceType.PHOTO, "SELECT photo_id AS id FROM categorization_job_photos WHERE job_id = :id", id))
        }
        AdminResourceType.PHOTO_RATING -> linkedSetOf<ResourceReference>().apply {
            add(ResourceReference(AdminResourceType.PHOTO, id))
            addAll(references(AdminResourceType.USER, "SELECT rated_by AS id FROM photo_ratings WHERE photo_id = :id", id))
        }
        AdminResourceType.SELECTION -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM photo_selections WHERE id = :id", id))
            addAll(references(AdminResourceType.PHOTO, "SELECT photo_id AS id FROM photo_selection_items WHERE selection_id = :id", id))
        }
        AdminResourceType.COLLABORATION -> linkedSetOf<ResourceReference>().apply {
            addAll(references(AdminResourceType.GALLERY, "SELECT gallery_id AS id FROM collab_sessions WHERE id = :id", id))
            addAll(references(AdminResourceType.PHOTO, """
                SELECT shared.photo_id AS id FROM (${sharedPhotosSql()}) shared
            """.trimIndent(), id))
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
                    (SELECT COUNT(*) FROM workspace_members wm JOIN studios s ON s.workspace_id = wm.workspace_id WHERE wm.user_id = :id AND wm.role = 'OWNER' AND wm.deleted_at IS NULL) AS owned_studios,
                    (SELECT COUNT(*) FROM workspace_members wm JOIN studios s ON s.workspace_id = wm.workspace_id WHERE wm.user_id = :id AND wm.deleted_at IS NULL) AS studio_memberships,
                    (SELECT COUNT(*) FROM gallery_members WHERE user_id = :id AND deleted_at IS NULL) AS joined_galleries,
                    GREATEST(
                        (SELECT MAX(updated_at) FROM refresh_tokens WHERE user_id = :id),
                        (SELECT MAX(updated_at) FROM gallery_members WHERE user_id = :id)
                    ) AS last_activity_at
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "activeSessions" to rs.getLong("active_sessions"),
            "ownedStudios" to rs.getLong("owned_studios"),
            "studioWorkspaceMemberships" to rs.getLong("studio_memberships"),
            "joinedGalleries" to rs.getLong("joined_galleries"),
            "lastActivityAt" to rs.getObject("last_activity_at"),
        ) }
        AdminResourceType.WORKSPACE -> singleFacts(
            """
                SELECT w.type,
                    EXISTS (SELECT 1 FROM studios s WHERE s.workspace_id = w.id) AS studio,
                    (SELECT COUNT(*) FROM workspace_members m WHERE m.workspace_id = w.id AND m.deleted_at IS NULL) AS members,
                    (SELECT COUNT(*) FROM workspace_members m WHERE m.workspace_id = w.id AND m.role = 'OWNER' AND m.deleted_at IS NULL) AS owners,
                    (SELECT COUNT(*) FROM galleries g WHERE g.workspace_id = w.id AND g.deleted_at IS NULL) AS galleries,
                    (SELECT COUNT(*) FROM photos p JOIN galleries g ON g.id = p.gallery_id WHERE g.workspace_id = w.id AND p.deleted_at IS NULL) AS photos
                FROM workspaces w WHERE w.id = :id
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "workspaceType" to rs.getString("type"),
            "studio" to rs.getBoolean("studio"),
            "members" to rs.getLong("members"),
            "owners" to rs.getLong("owners"),
            "galleries" to rs.getLong("galleries"),
            "photos" to rs.getLong("photos"),
        ) }
        AdminResourceType.STUDIO -> singleFacts(
            """
                SELECT (SELECT wm.user_id FROM workspace_members wm WHERE wm.workspace_id = :id AND wm.role = 'OWNER' AND wm.deleted_at IS NULL ORDER BY wm.id LIMIT 1) AS owner_id,
                    (SELECT COUNT(*) FROM galleries WHERE workspace_id = :id) AS galleries,
                    (SELECT COUNT(*) FROM photos p JOIN galleries g ON g.id = p.gallery_id WHERE g.workspace_id = :id) AS photos,
                    (SELECT COALESCE(SUM(p.byte_size), 0) FROM photos p JOIN galleries g ON g.id = p.gallery_id WHERE g.workspace_id = :id) AS storage_bytes,
                    (SELECT COUNT(*) FROM workspace_members WHERE workspace_id = :id AND deleted_at IS NULL) AS members
                FROM studios s WHERE s.workspace_id = :id
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "ownerId" to rs.getLong("owner_id"),
            "galleries" to rs.getLong("galleries"),
            "photos" to rs.getLong("photos"),
            "storageBytes" to rs.getLong("storage_bytes"),
            "members" to rs.getLong("members"),
        ) }
        AdminResourceType.GALLERY -> singleFacts(
            """
                SELECT g.status AS public_status, g.workflow_status, g.stage, g.selection_deadline,
                    g.max_selectable_photo_count,
                    (SELECT COUNT(*) FROM gallery_members WHERE gallery_id = :id AND deleted_at IS NULL) AS members,
                    (SELECT COUNT(*) FROM photos WHERE gallery_id = :id) AS photos,
                    (SELECT COUNT(*) FROM concept_folders WHERE gallery_id = :id AND deleted_at IS NULL) AS concept_folders,
                    (SELECT COUNT(*) FROM detail_folders d JOIN concept_folders c ON c.id = d.concept_folder_id WHERE c.gallery_id = :id AND d.deleted_at IS NULL AND c.deleted_at IS NULL) AS detail_folders,
                    (SELECT COUNT(*) FROM photo_category_assignments a JOIN detail_folders d ON d.id = a.detail_folder_id JOIN concept_folders c ON c.id = d.concept_folder_id WHERE c.gallery_id = :id) AS category_assignments,
                    (SELECT COUNT(*) FROM categorization_jobs WHERE gallery_id = :id) AS categorization_jobs,
                    (SELECT COUNT(*) FROM photo_ratings r JOIN photos p ON p.id = r.photo_id WHERE p.gallery_id = :id) AS ratings,
                    (SELECT COUNT(*) FROM photo_selection_items i JOIN photo_selections s ON s.id = i.selection_id WHERE s.gallery_id = :id) AS selected_photos,
                    (SELECT CASE WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                                      WHEN expires_at < CURRENT_TIMESTAMP THEN 'EXPIRED'
                                      WHEN used_count >= max_uses THEN 'FULL'
                                      ELSE 'ACTIVE' END
                       FROM gallery_invites WHERE gallery_id = :id ORDER BY id DESC LIMIT 1) AS invite_status,
                    (SELECT expires_at FROM gallery_invites WHERE gallery_id = :id ORDER BY id DESC LIMIT 1) AS invite_expires_at,
                    EXISTS (
                        SELECT 1 FROM photo_selections s
                        WHERE s.gallery_id = :id AND s.status = 'SUBMITTED'
                    ) AS submitted
                FROM galleries g WHERE g.id = :id
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "publicStatus" to rs.getString("public_status"),
            "workflowStatus" to rs.getString("workflow_status"),
            "stage" to rs.getString("stage"),
            "selectionDeadline" to rs.getObject("selection_deadline"),
            "targetPhotoCount" to rs.getObject("max_selectable_photo_count"),
            "members" to rs.getLong("members"),
            "photos" to rs.getLong("photos"),
            "conceptFolders" to rs.getLong("concept_folders"),
            "detailFolders" to rs.getLong("detail_folders"),
            "categoryAssignments" to rs.getLong("category_assignments"),
            "categorizationJobs" to rs.getLong("categorization_jobs"),
            "ratings" to rs.getLong("ratings"),
            "selectedPhotos" to rs.getLong("selected_photos"),
            "inviteStatus" to rs.getString("invite_status"),
            "inviteExpiresAt" to rs.getObject("invite_expires_at"),
            "submitted" to rs.getBoolean("submitted"),
        ) }
        AdminResourceType.PHOTO -> singleFacts(
            """
                SELECT byte_size, width, height, camera_make, camera_model, taken_at,
                       EXISTS (SELECT 1 FROM photo_analysis a WHERE a.photo_id = photos.id AND a.embedding IS NOT NULL) AS analyzed,
                       (SELECT COUNT(*) FROM photo_selection_items WHERE photo_id = :id) AS selection_references,
                       (SELECT COUNT(*) FROM retouch_photos WHERE photo_id = :id) AS retouch_references,
                       (SELECT detail_folder_id FROM photo_category_assignments WHERE photo_id = :id) AS detail_folder_id,
                       (SELECT assigned_source FROM photo_category_assignments WHERE photo_id = :id) AS category_source,
                       (SELECT score FROM photo_ratings WHERE photo_id = :id) AS rating_score,
                       (SELECT rated_by FROM photo_ratings WHERE photo_id = :id) AS rated_by_user_id
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
            "selectionReferences" to rs.getLong("selection_references"),
            "retouchReferences" to rs.getLong("retouch_references"),
            "detailFolderId" to rs.getObject("detail_folder_id"),
            "categorySource" to rs.getString("category_source"),
            "ratingScore" to rs.getObject("rating_score"),
            "ratedByUserId" to rs.getObject("rated_by_user_id"),
        ) }
        AdminResourceType.CONCEPT_FOLDER -> singleFacts(
            """
                SELECT
                    (SELECT COUNT(*) FROM detail_folders WHERE concept_folder_id = :id AND deleted_at IS NULL) AS detail_folders,
                    (SELECT COUNT(*) FROM photo_category_assignments a JOIN detail_folders d ON d.id = a.detail_folder_id WHERE d.concept_folder_id = :id) AS assigned_photos,
                    (SELECT COUNT(*) FROM collab_sessions WHERE concept_folder_id = :id AND deleted_at IS NULL) AS collaboration_sessions
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "detailFolders" to rs.getLong("detail_folders"),
            "assignedPhotos" to rs.getLong("assigned_photos"),
            "collaborationSessions" to rs.getLong("collaboration_sessions"),
        ) }
        AdminResourceType.DETAIL_FOLDER -> singleFacts(
            "SELECT COUNT(*) AS assigned_photos FROM photo_category_assignments WHERE detail_folder_id = :id",
            id,
        ) { rs -> linkedMapOf("assignedPhotos" to rs.getLong("assigned_photos")) }
        AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT -> singleFacts(
            """
                SELECT p.gallery_id, c.id AS concept_folder_id
                FROM photo_category_assignments a
                JOIN photos p ON p.id = a.photo_id
                JOIN detail_folders d ON d.id = a.detail_folder_id
                JOIN concept_folders c ON c.id = d.concept_folder_id
                WHERE a.photo_id = :id
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "galleryId" to rs.getLong("gallery_id"),
            "conceptFolderId" to rs.getLong("concept_folder_id"),
        ) }
        AdminResourceType.CATEGORIZATION_JOB -> singleFacts(
            "SELECT COUNT(*) AS processed_photos FROM categorization_job_photos WHERE job_id = :id",
            id,
        ) { rs -> linkedMapOf("processedPhotos" to rs.getLong("processed_photos")) }
        AdminResourceType.PHOTO_RATING -> singleFacts(
            "SELECT p.gallery_id FROM photo_ratings r JOIN photos p ON p.id = r.photo_id WHERE r.photo_id = :id",
            id,
        ) { rs -> linkedMapOf("galleryId" to rs.getLong("gallery_id")) }
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
                    (SELECT COUNT(*) FROM collab_participants WHERE collab_session_id = :id AND deleted_at IS NULL) AS participants,
                    (SELECT COUNT(*) FROM (${sharedPhotosSql()}) shared) AS photos,
                    (SELECT COUNT(*) FROM collab_photo_comments c WHERE c.collab_session_id = :id) AS comments,
                    (SELECT COUNT(*) FROM collab_photo_likes l WHERE l.collab_session_id = :id AND l.deleted_at IS NULL) AS likes
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "participants" to rs.getLong("participants"),
            "photos" to rs.getLong("photos"),
            "comments" to rs.getLong("comments"),
            "likes" to rs.getLong("likes"),
        ) }
        AdminResourceType.RETOUCH_REQUEST -> singleFacts(
            """
                SELECT r.selection_revision_id, r.customer_consented_at, r.delivered_at, r.delivery_note,
                       COUNT(p.*) FILTER (WHERE p.deleted_at IS NULL) AS photos,
                       COUNT(p.*) FILTER (WHERE p.deleted_at IS NULL AND p.result_key IS NOT NULL) AS completed_results,
                       COUNT(p.*) FILTER (WHERE p.deleted_at IS NULL AND p.result_key IS NULL) AS pending_results
                FROM retouch_rounds r
                LEFT JOIN retouch_photos p ON p.round_id = r.id
                WHERE r.id = :id
                GROUP BY r.id
            """.trimIndent(), id,
        ) { rs -> linkedMapOf(
            "selectionRevisionId" to rs.getObject("selection_revision_id"),
            "customerConsentedAt" to rs.getObject("customer_consented_at"),
            "deliveredAt" to rs.getObject("delivered_at"),
            "deliveryNote" to rs.getString("delivery_note"),
            "photos" to rs.getLong("photos"),
            "completedResults" to rs.getLong("completed_results"),
            "pendingResults" to rs.getLong("pending_results"),
        ) }
    }

    fun findSections(type: AdminResourceType, id: Long): Map<String, List<Map<String, Any?>>> = when (type) {
        AdminResourceType.USER -> linkedMapOf(
            "workspaces" to rows(
                """
                    SELECT w.id AS workspace_id, w.type AS workspace_type, w.name AS workspace_name,
                           m.id AS member_id, m.role AS access_role, m.deleted_at, m.created_at
                    FROM workspace_members m
                    JOIN workspaces w ON w.id = m.workspace_id
                    WHERE m.user_id = :id
                    ORDER BY w.type, w.id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "workspaceId" to rs.getLong("workspace_id"),
                "workspaceType" to rs.getString("workspace_type"),
                "workspaceName" to rs.getString("workspace_name"),
                "memberId" to rs.getLong("member_id"),
                "accessRole" to rs.getString("access_role"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "joinedAt" to rs.getObject("created_at"),
            ) },
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
                    SELECT m.id AS member_id, m.gallery_id, g.title, g.status, g.workflow_status,
                           g.stage, m.created_at
                    FROM gallery_members m JOIN galleries g ON g.id = m.gallery_id
                    WHERE m.user_id = :id ORDER BY m.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "memberId" to rs.getLong("member_id"),
                "galleryId" to rs.getLong("gallery_id"),
                "galleryTitle" to rs.getString("title"),
                "galleryStatus" to rs.getString("status"),
                "workflowStatus" to rs.getString("workflow_status"),
                "stage" to rs.getString("stage"),
                "joinedAt" to rs.getObject("created_at"),
            ) },
            "userNotifications" to rows(
                """
                    SELECT id, user_id, type, scope, scope_id, title, message, read_at, created_at
                    FROM user_notifications
                    WHERE user_id = :id
                    ORDER BY created_at DESC, id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> userNotification(rs) },
            "userNotificationSettings" to rows(
                """
                    SELECT u.id AS user_id,
                           COALESCE(s.email_enabled, TRUE) AS email_enabled,
                           COALESCE(s.browser_enabled, TRUE) AS browser_enabled,
                           (s.user_id IS NOT NULL) AS persisted,
                           COALESCE(s.version, 0) AS version,
                           s.updated_at
                    FROM users u
                    LEFT JOIN user_notification_settings s ON s.user_id = u.id
                    WHERE u.id = :id
                """.trimIndent(),
                id,
            ) { rs -> userNotificationSettings(rs) },
        )
        AdminResourceType.WORKSPACE -> linkedMapOf(
            "members" to rows(
                """
                    SELECT m.id AS member_id, m.user_id, m.role AS access_role, m.version,
                           m.deleted_at, m.created_at, u.nickname, u.email
                    FROM workspace_members m
                    JOIN users u ON u.id = m.user_id
                    WHERE m.workspace_id = :id
                    ORDER BY m.role, m.id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "memberId" to rs.getLong("member_id"),
                "userId" to rs.getLong("user_id"),
                "accessRole" to rs.getString("access_role"),
                "nickname" to rs.getString("nickname"),
                "email" to rs.getString("email"),
                "version" to rs.getLong("version"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "joinedAt" to rs.getObject("created_at"),
            ) },
            "studio" to rows(
                """
                    SELECT workspace_id, name, gallery_url, inflow_channel, contact, description,
                           suspended_at, deleted_at
                    FROM studios WHERE workspace_id = :id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "workspaceId" to rs.getLong("workspace_id"),
                "name" to rs.getString("name"),
                "galleryUrl" to rs.getString("gallery_url"),
                "inflowChannel" to rs.getString("inflow_channel"),
                "contact" to rs.getString("contact"),
                "description" to rs.getString("description"),
                "suspended" to (rs.getObject("suspended_at") != null),
                "deleted" to (rs.getObject("deleted_at") != null),
            ) },
            "galleries" to rows(
                """
                    SELECT id, created_by_user_id, title, status, workflow_status, stage,
                           selection_deadline, deleted_at, created_at
                    FROM galleries WHERE workspace_id = :id ORDER BY id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "galleryId" to rs.getLong("id"),
                "createdByUserId" to rs.getObject("created_by_user_id"),
                "title" to rs.getString("title"),
                "publicStatus" to rs.getString("status"),
                "workflowStatus" to rs.getString("workflow_status"),
                "stage" to rs.getString("stage"),
                "selectionDeadline" to rs.getObject("selection_deadline"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "createdAt" to rs.getObject("created_at"),
            ) },
        )
        AdminResourceType.STUDIO -> linkedMapOf(
            "owner" to rows(
                """
                    SELECT u.id AS user_id, u.nickname, u.email, u.provider
                    FROM workspace_members m JOIN users u ON u.id = m.user_id
                    WHERE m.workspace_id = :id AND m.role = 'OWNER' AND m.deleted_at IS NULL
                    ORDER BY m.id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "userId" to rs.getLong("user_id"),
                "nickname" to rs.getString("nickname"),
                "email" to rs.getString("email"),
                "provider" to rs.getString("provider"),
            ) },
            "members" to rows(
                """
                    SELECT m.id AS member_id, m.user_id, m.role, m.deleted_at,
                           u.nickname, u.email, m.created_at
                    FROM workspace_members m JOIN users u ON u.id = m.user_id
                    WHERE m.workspace_id = :id ORDER BY m.role, m.id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "memberId" to rs.getLong("member_id"),
                "userId" to rs.getLong("user_id"),
                "accessRole" to rs.getString("role"),
                "nickname" to rs.getString("nickname"),
                "email" to rs.getString("email"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "joinedAt" to rs.getObject("created_at"),
            ) },
            "retouchCapabilities" to rows(
                """
                    SELECT capability, enabled, updated_at
                    FROM studio_retouch_capabilities WHERE studio_id = :id ORDER BY capability
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "capability" to rs.getString("capability"),
                "enabled" to rs.getBoolean("enabled"),
                "updatedAt" to rs.getObject("updated_at"),
            ) },
            "galleries" to rows(
                """
                    SELECT id, workspace_id, created_by_user_id, title, status, workflow_status, stage,
                           selection_deadline, deleted_at, created_at
                    FROM galleries WHERE workspace_id = :id ORDER BY id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "workspaceId" to rs.getLong("workspace_id"),
                "workspaceType" to "STUDIO",
                "createdByUserId" to rs.getObject("created_by_user_id"),
                "title" to rs.getString("title"),
                "publicStatus" to rs.getString("status"),
                "workflowStatus" to rs.getString("workflow_status"),
                "stage" to rs.getString("stage"),
                "selectionDeadline" to rs.getObject("selection_deadline"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "createdAt" to rs.getObject("created_at"),
            ) },
            "userNotifications" to rows(
                """
                    SELECT id, user_id, type, scope, scope_id, title, message, read_at, created_at
                    FROM user_notifications
                    WHERE scope = 'STUDIO' AND scope_id = :id
                    ORDER BY created_at DESC, id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> userNotification(rs) },
            "userNotificationSettings" to rows(
                """
                    SELECT u.id AS user_id, u.nickname,
                           COALESCE(s.email_enabled, TRUE) AS email_enabled,
                           COALESCE(s.browser_enabled, TRUE) AS browser_enabled,
                           (s.user_id IS NOT NULL) AS persisted,
                           COALESCE(s.version, 0) AS version,
                           s.updated_at
                    FROM workspace_members m
                    JOIN users u ON u.id = m.user_id
                    LEFT JOIN user_notification_settings s ON s.user_id = u.id
                    WHERE m.workspace_id = :id AND m.deleted_at IS NULL
                    ORDER BY u.id
                """.trimIndent(),
                id,
            ) { rs -> userNotificationSettings(rs, includeNickname = true) },
        )
        AdminResourceType.GALLERY -> linkedMapOf(
            "members" to rows(
                """
                    SELECT m.id AS member_id, u.id AS user_id, u.nickname, u.email,
                           m.deleted_at, m.created_at
                    FROM gallery_members m JOIN users u ON u.id = m.user_id
                    WHERE m.gallery_id = :id ORDER BY m.id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "memberId" to rs.getLong("member_id"),
                "userId" to rs.getLong("user_id"),
                "nickname" to rs.getString("nickname"),
                "email" to rs.getString("email"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "joinedAt" to rs.getObject("created_at"),
            ) },
            "invites" to rows(
                """
                    SELECT id, kind, max_uses, used_count, expires_at, revoked_at, created_at,
                           CASE WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                                WHEN expires_at < CURRENT_TIMESTAMP THEN 'EXPIRED'
                                WHEN used_count >= max_uses THEN 'FULL'
                                ELSE 'ACTIVE' END AS status
                    FROM gallery_invites WHERE gallery_id = :id ORDER BY id DESC LIMIT 20
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "kind" to rs.getString("kind"),
                "maxUses" to rs.getInt("max_uses"),
                "usedCount" to rs.getInt("used_count"),
                "remainingUses" to (rs.getInt("max_uses") - rs.getInt("used_count")).coerceAtLeast(0),
                "status" to rs.getString("status"),
                "expiresAt" to rs.getObject("expires_at"),
                "revokedAt" to rs.getObject("revoked_at"),
                "createdAt" to rs.getObject("created_at"),
                "token" to "[MASKED]",
            ) },
            "userNotifications" to rows(
                """
                    SELECT id, user_id, type, scope, scope_id, title, message, read_at, created_at
                    FROM user_notifications
                    WHERE scope = 'GALLERY' AND scope_id = :id
                    ORDER BY created_at DESC, id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> userNotification(rs) },
            "userNotificationSettings" to rows(
                """
                    WITH related_users AS (
                        SELECT wm.user_id
                        FROM galleries g
                        JOIN workspace_members wm ON wm.workspace_id = g.workspace_id
                        WHERE g.id = :id AND wm.deleted_at IS NULL
                        UNION
                        SELECT gm.user_id
                        FROM gallery_members gm
                        WHERE gm.gallery_id = :id AND gm.deleted_at IS NULL
                    )
                    SELECT u.id AS user_id, u.nickname,
                           COALESCE(s.email_enabled, TRUE) AS email_enabled,
                           COALESCE(s.browser_enabled, TRUE) AS browser_enabled,
                           (s.user_id IS NOT NULL) AS persisted,
                           COALESCE(s.version, 0) AS version,
                           s.updated_at
                    FROM related_users r
                    JOIN users u ON u.id = r.user_id
                    LEFT JOIN user_notification_settings s ON s.user_id = u.id
                    ORDER BY u.id
                """.trimIndent(),
                id,
            ) { rs -> userNotificationSettings(rs, includeNickname = true) },
            "conceptFolders" to rows(
                """
                    SELECT c.id, c.name, c.sort_order, c.created_source, c.version, c.deleted_at,
                           (SELECT COUNT(*) FROM detail_folders d WHERE d.concept_folder_id = c.id AND d.deleted_at IS NULL) AS detail_count,
                           (SELECT COUNT(*) FROM photo_category_assignments a JOIN detail_folders d ON d.id = a.detail_folder_id WHERE d.concept_folder_id = c.id) AS photo_count,
                           (SELECT id FROM collab_sessions s WHERE s.concept_folder_id = c.id AND s.deleted_at IS NULL) AS collaboration_id
                    FROM concept_folders c WHERE c.gallery_id = :id
                    ORDER BY c.sort_order, c.id LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "name" to rs.getString("name"),
                "sortOrder" to rs.getInt("sort_order"),
                "createdSource" to rs.getString("created_source"),
                "version" to rs.getLong("version"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "detailCount" to rs.getLong("detail_count"),
                "photoCount" to rs.getLong("photo_count"),
                "collaborationId" to rs.getObject("collaboration_id"),
            ) },
            "detailFolders" to rows(
                """
                    SELECT d.id, d.concept_folder_id, d.name, d.sort_order, d.created_source,
                           d.version, d.deleted_at,
                           (SELECT COUNT(*) FROM photo_category_assignments a WHERE a.detail_folder_id = d.id) AS photo_count
                    FROM detail_folders d
                    JOIN concept_folders c ON c.id = d.concept_folder_id
                    WHERE c.gallery_id = :id
                    ORDER BY c.sort_order, d.sort_order, d.id LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "conceptFolderId" to rs.getLong("concept_folder_id"),
                "name" to rs.getString("name"),
                "sortOrder" to rs.getInt("sort_order"),
                "createdSource" to rs.getString("created_source"),
                "version" to rs.getLong("version"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "photoCount" to rs.getLong("photo_count"),
            ) },
            "categoryAssignments" to rows(
                """
                    SELECT a.photo_id, a.detail_folder_id, d.concept_folder_id, p.original_file_name,
                           a.assigned_by_user_id, a.assigned_source, a.confidence, a.assigned_at, a.version
                    FROM photo_category_assignments a
                    JOIN photos p ON p.id = a.photo_id
                    JOIN detail_folders d ON d.id = a.detail_folder_id
                    JOIN concept_folders c ON c.id = d.concept_folder_id
                    WHERE c.gallery_id = :id
                    ORDER BY a.assigned_at DESC, a.photo_id LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "photoId" to rs.getLong("photo_id"),
                "detailFolderId" to rs.getLong("detail_folder_id"),
                "conceptFolderId" to rs.getLong("concept_folder_id"),
                "fileName" to rs.getString("original_file_name"),
                "assignedByUserId" to rs.getObject("assigned_by_user_id"),
                "assignedSource" to rs.getString("assigned_source"),
                "confidence" to rs.getObject("confidence"),
                "assignedAt" to rs.getObject("assigned_at"),
                "version" to rs.getLong("version"),
            ) },
            "categorizationJobs" to rows(
                """
                    SELECT j.id, j.mode, j.status, j.started_at, j.completed_at, j.failure_code,
                           j.version, COUNT(p.photo_id) AS photo_count
                    FROM categorization_jobs j
                    LEFT JOIN categorization_job_photos p ON p.job_id = j.id
                    WHERE j.gallery_id = :id
                    GROUP BY j.id
                    ORDER BY j.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "mode" to rs.getString("mode"),
                "status" to rs.getString("status"),
                "startedAt" to rs.getObject("started_at"),
                "completedAt" to rs.getObject("completed_at"),
                "failureCode" to rs.getString("failure_code"),
                "version" to rs.getLong("version"),
                "photoCount" to rs.getLong("photo_count"),
            ) },
            "photoRatings" to rows(
                """
                    SELECT r.id, r.photo_id, r.score, r.rated_by, r.version, r.created_at, r.updated_at
                    FROM photo_ratings r JOIN photos p ON p.id = r.photo_id
                    WHERE p.gallery_id = :id ORDER BY r.updated_at DESC, r.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("photo_id"),
                "ratingRowId" to rs.getLong("id"),
                "photoId" to rs.getLong("photo_id"),
                "score" to rs.getInt("score"),
                "ratedByUserId" to rs.getLong("rated_by"),
                "version" to rs.getLong("version"),
                "createdAt" to rs.getObject("created_at"),
                "updatedAt" to rs.getObject("updated_at"),
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
                    SELECT id, name, revoked_at, expires_at, created_at FROM collab_sessions
                    WHERE gallery_id = :id ORDER BY id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "name" to rs.getString("name"),
                "status" to when {
                    rs.getObject("revoked_at") != null -> "REVOKED"
                    rs.getObject("expires_at") != null &&
                        (rs.getObject("expires_at") as java.time.OffsetDateTime).isBefore(java.time.OffsetDateTime.now()) -> "EXPIRED"
                    else -> "ACTIVE"
                },
                "token" to "[MASKED]",
                "expiresAt" to rs.getObject("expires_at"),
                "createdAt" to rs.getObject("created_at"),
            ) },
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
            "categoryAssignment" to rows(
                """
                    SELECT a.photo_id, a.detail_folder_id, d.concept_folder_id,
                           a.assigned_by_user_id, a.assigned_source, a.confidence,
                           a.assigned_at, a.version
                    FROM photo_category_assignments a
                    JOIN detail_folders d ON d.id = a.detail_folder_id
                    WHERE a.photo_id = :id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "photoId" to rs.getLong("photo_id"),
                "detailFolderId" to rs.getLong("detail_folder_id"),
                "conceptFolderId" to rs.getLong("concept_folder_id"),
                "assignedByUserId" to rs.getObject("assigned_by_user_id"),
                "assignedSource" to rs.getString("assigned_source"),
                "confidence" to rs.getObject("confidence"),
                "assignedAt" to rs.getObject("assigned_at"),
                "version" to rs.getLong("version"),
            ) },
            "categorizationJobs" to rows(
                """
                    SELECT j.id, j.gallery_id, j.mode, j.status, j.started_at,
                           j.completed_at, j.failure_code, j.version
                    FROM categorization_job_photos p
                    JOIN categorization_jobs j ON j.id = p.job_id
                    WHERE p.photo_id = :id ORDER BY j.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "galleryId" to rs.getLong("gallery_id"),
                "mode" to rs.getString("mode"),
                "status" to rs.getString("status"),
                "startedAt" to rs.getObject("started_at"),
                "completedAt" to rs.getObject("completed_at"),
                "failureCode" to rs.getString("failure_code"),
                "version" to rs.getLong("version"),
            ) },
            "rating" to rows(
                """
                    SELECT photo_id, score, rated_by, version, created_at, updated_at
                    FROM photo_ratings WHERE photo_id = :id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "photoId" to rs.getLong("photo_id"),
                "score" to rs.getInt("score"),
                "ratedByUserId" to rs.getLong("rated_by"),
                "version" to rs.getLong("version"),
                "createdAt" to rs.getObject("created_at"),
                "updatedAt" to rs.getObject("updated_at"),
            ) },
            "processingJobs" to rows(
                """
                    SELECT id, job_type, status, revision_id, attempt_count, failure_code,
                           last_run_at, created_at, updated_at
                    FROM admin_processing_jobs
                    WHERE target_type = 'PHOTO' AND target_id = :id
                    ORDER BY id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "jobType" to rs.getString("job_type"),
                "status" to rs.getString("status"),
                "revisionId" to rs.getObject("revision_id"),
                "attemptCount" to rs.getInt("attempt_count"),
                "failureCode" to rs.getString("failure_code"),
                "lastRunAt" to rs.getObject("last_run_at"),
                "createdAt" to rs.getObject("created_at"),
                "updatedAt" to rs.getObject("updated_at"),
            ) },
            "notifications" to rows(
                """
                    SELECT id, event_type, work_status, safe_summary, correlation_id,
                           version, created_at, updated_at
                    FROM admin_notification_inbox
                    WHERE target_type = 'PHOTO' AND target_id = :id
                    ORDER BY id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "notificationType" to rs.getString("event_type"),
                "status" to rs.getString("work_status"),
                "summary" to rs.getString("safe_summary"),
                "correlationId" to rs.getString("correlation_id"),
                "version" to rs.getLong("version"),
                "createdAt" to rs.getObject("created_at"),
                "updatedAt" to rs.getObject("updated_at"),
            ) },
            "replacementUploads" to rows(
                """
                    SELECT id, original_file_name, content_type, status, expires_at, created_at, completed_at
                    FROM admin_photo_replacement_uploads WHERE photo_id = :id ORDER BY id DESC LIMIT 20
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "originalFileName" to rs.getString("original_file_name"),
                "contentType" to rs.getString("content_type"),
                "status" to rs.getString("status"),
                "expiresAt" to rs.getObject("expires_at"),
                "createdAt" to rs.getObject("created_at"),
                "completedAt" to rs.getObject("completed_at"),
            ) },
            "revisions" to rows(
                """
                    SELECT id, revision_number, original_file_name, content_type, created_at
                    FROM admin_photo_revisions WHERE photo_id = :id ORDER BY revision_number DESC
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "revisionNumber" to rs.getLong("revision_number"),
                "originalFileName" to rs.getString("original_file_name"),
                "contentType" to rs.getString("content_type"),
                "createdAt" to rs.getObject("created_at"),
            ) },
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
        AdminResourceType.CONCEPT_FOLDER -> linkedMapOf(
            "detailFolders" to rows(
                """
                    SELECT d.id, d.name, d.sort_order, d.created_source, d.version, d.deleted_at,
                           COUNT(a.photo_id) AS photo_count
                    FROM detail_folders d
                    LEFT JOIN photo_category_assignments a ON a.detail_folder_id = d.id
                    WHERE d.concept_folder_id = :id
                    GROUP BY d.id ORDER BY d.sort_order, d.id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "name" to rs.getString("name"),
                "sortOrder" to rs.getInt("sort_order"),
                "createdSource" to rs.getString("created_source"),
                "version" to rs.getLong("version"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "photoCount" to rs.getLong("photo_count"),
            ) },
            "collaboration" to rows(
                """
                    SELECT id, gallery_id, name, revoked_at, expires_at, version, deleted_at, created_at
                    FROM collab_sessions WHERE concept_folder_id = :id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "galleryId" to rs.getLong("gallery_id"),
                "name" to rs.getString("name"),
                "revokedAt" to rs.getObject("revoked_at"),
                "expiresAt" to rs.getObject("expires_at"),
                "version" to rs.getLong("version"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "createdAt" to rs.getObject("created_at"),
            ) },
            "categoryAssignments" to categoryAssignmentRows(
                "WHERE d.concept_folder_id = :id ORDER BY a.assigned_at DESC, a.photo_id LIMIT 100",
                id,
            ),
        )
        AdminResourceType.DETAIL_FOLDER -> linkedMapOf(
            "categoryAssignments" to categoryAssignmentRows(
                "WHERE a.detail_folder_id = :id ORDER BY a.assigned_at DESC, a.photo_id LIMIT 100",
                id,
            ),
        )
        AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT -> linkedMapOf(
            "categorizationJobs" to rows(
                """
                    SELECT j.id, j.gallery_id, j.mode, j.status, j.started_at, j.completed_at,
                           j.failure_code, j.version
                    FROM categorization_job_photos p
                    JOIN categorization_jobs j ON j.id = p.job_id
                    WHERE p.photo_id = :id ORDER BY j.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "galleryId" to rs.getLong("gallery_id"),
                "mode" to rs.getString("mode"),
                "status" to rs.getString("status"),
                "startedAt" to rs.getObject("started_at"),
                "completedAt" to rs.getObject("completed_at"),
                "failureCode" to rs.getString("failure_code"),
                "version" to rs.getLong("version"),
            ) },
        )
        AdminResourceType.CATEGORIZATION_JOB -> linkedMapOf(
            "photos" to rows(
                """
                    SELECT p.photo_id, p.gallery_id, photo.original_file_name,
                           photo.status AS upload_status, p.status AS categorization_status,
                           p.failure_code, p.processed_at,
                           a.detail_folder_id, a.assigned_source
                    FROM categorization_job_photos p
                    JOIN photos photo ON photo.id = p.photo_id
                    LEFT JOIN photo_category_assignments a ON a.photo_id = p.photo_id
                    WHERE p.job_id = :id ORDER BY p.photo_id LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "photoId" to rs.getLong("photo_id"),
                "galleryId" to rs.getLong("gallery_id"),
                "fileName" to rs.getString("original_file_name"),
                "photoStatus" to rs.getString("upload_status"),
                "categorizationStatus" to rs.getString("categorization_status"),
                "failureCode" to rs.getString("failure_code"),
                "processedAt" to rs.getObject("processed_at"),
                "detailFolderId" to rs.getObject("detail_folder_id"),
                "assignedSource" to rs.getString("assigned_source"),
            ) },
        )
        AdminResourceType.PHOTO_RATING -> emptyMap()
        AdminResourceType.SELECTION -> linkedMapOf(
            "aiJobs" to rows(
                """
                    SELECT id, status, input_conditions, result_revision_id, failure_code,
                           attempt_count, created_at, started_at, completed_at
                    FROM admin_ai_selection_jobs WHERE selection_id = :id ORDER BY id DESC
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "status" to rs.getString("status"),
                "inputConditions" to rs.getString("input_conditions"),
                "resultRevisionId" to rs.getObject("result_revision_id"),
                "failureCode" to rs.getString("failure_code"),
                "attemptCount" to rs.getInt("attempt_count"),
                "createdAt" to rs.getObject("created_at"),
                "startedAt" to rs.getObject("started_at"),
                "completedAt" to rs.getObject("completed_at"),
            ) },
            "revisions" to rows(
                """
                    SELECT id, revision_number, source, status, photo_items, actor_admin_id, reason, created_at
                    FROM admin_selection_revisions WHERE selection_id = :id ORDER BY revision_number DESC
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "revisionNumber" to rs.getLong("revision_number"),
                "source" to rs.getString("source"),
                "status" to rs.getString("status"),
                "photoItems" to rs.getString("photo_items"),
                "actorAdminId" to rs.getObject("actor_admin_id"),
                "reason" to rs.getString("reason"),
                "createdAt" to rs.getObject("created_at"),
            ) },
            "items" to rows(
                """
                    SELECT i.id, i.gallery_id, i.photo_id, p.original_file_name,
                           i.retouch_photo_id, i.added_by_user_id, i.sort_order, i.created_at
                    FROM photo_selection_items i JOIN photos p ON p.id = i.photo_id
                    WHERE i.selection_id = :id ORDER BY i.id LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "galleryId" to rs.getLong("gallery_id"),
                "photoId" to rs.getLong("photo_id"),
                "fileName" to rs.getString("original_file_name"),
                "retouchPhotoId" to rs.getObject("retouch_photo_id"),
                "addedByUserId" to rs.getObject("added_by_user_id"),
                "sortOrder" to rs.getInt("sort_order"),
                "selectedAt" to rs.getObject("created_at"),
            ) },
        )
        AdminResourceType.COLLABORATION -> linkedMapOf(
            "sharedPhotos" to rows(
                """
                    SELECT p.id AS photo_id, p.original_file_name,
                           p.status, shared.created_at
                    FROM (${sharedPhotosSql()}) shared
                    JOIN photos p ON p.id = shared.photo_id
                    ORDER BY p.id LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "photoId" to rs.getLong("photo_id"),
                "fileName" to rs.getString("original_file_name"),
                "status" to rs.getString("status"),
                "createdAt" to rs.getObject("created_at"),
            ) },
            "participants" to rows(
                "SELECT id, participant_type, user_id, nickname, created_at FROM collab_participants WHERE collab_session_id = :id ORDER BY id LIMIT 100",
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "participantType" to rs.getString("participant_type"),
                "userId" to rs.getLong("user_id").takeUnless { rs.wasNull() },
                "nickname" to rs.getString("nickname"),
                "createdAt" to rs.getObject("created_at"),
            ) },
            "comments" to rows(
                """
                    SELECT c.id, c.photo_id, participant.id AS participant_id,
                           participant.participant_type, participant.user_id, participant.nickname,
                           c.content, c.version, c.deleted_at, c.created_at
                    FROM collab_photo_comments c
                    JOIN collab_participants participant ON participant.id = c.participant_id
                    WHERE c.collab_session_id = :id ORDER BY c.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "photoId" to rs.getLong("photo_id"),
                "participantId" to rs.getLong("participant_id"),
                "participantType" to rs.getString("participant_type"),
                "userId" to rs.getLong("user_id").takeUnless { rs.wasNull() },
                "nickname" to rs.getString("nickname"),
                "content" to rs.getString("content"),
                "version" to rs.getLong("version"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "createdAt" to rs.getObject("created_at"),
            ) },
            "likes" to rows(
                """
                    SELECT l.id, l.photo_id, participant.id AS participant_id,
                           participant.participant_type, participant.user_id,
                           participant.nickname, l.version, l.deleted_at, l.created_at
                    FROM collab_photo_likes l
                    JOIN collab_participants participant ON participant.id = l.participant_id
                    WHERE l.collab_session_id = :id ORDER BY l.id DESC LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "photoId" to rs.getLong("photo_id"),
                "participantId" to rs.getLong("participant_id"),
                "participantType" to rs.getString("participant_type"),
                "userId" to rs.getLong("user_id").takeUnless { rs.wasNull() },
                "nickname" to rs.getString("nickname"),
                "version" to rs.getLong("version"),
                "deleted" to (rs.getObject("deleted_at") != null),
                "createdAt" to rs.getObject("created_at"),
            ) },
        )
        AdminResourceType.RETOUCH_REQUEST -> linkedMapOf(
            "items" to rows(
                """
                    SELECT r.id, r.photo_id, p.original_file_name, r.request_text, r.version,
                           r.annotation_key IS NOT NULL AS annotated,
                           r.result_key IS NOT NULL AS result_ready, r.result_content_type,
                           r.structured_ai_metadata, r.deleted_at
                    FROM retouch_photos r JOIN photos p ON p.id = r.photo_id
                    WHERE r.round_id = :id ORDER BY r.id LIMIT 100
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "id" to rs.getLong("id"),
                "photoId" to rs.getLong("photo_id"),
                "fileName" to rs.getString("original_file_name"),
                "requestText" to rs.getString("request_text"),
                "version" to rs.getLong("version"),
                "annotated" to rs.getBoolean("annotated"),
                "resultReady" to rs.getBoolean("result_ready"),
                "resultContentType" to rs.getString("result_content_type"),
                "structuredAiMetadata" to rs.getString("structured_ai_metadata"),
                "deleted" to (rs.getObject("deleted_at") != null),
            ) },
            "delivery" to rows(
                """
                    SELECT selection_revision_id, customer_consented_at, delivered_at, delivery_note
                    FROM retouch_rounds WHERE id = :id
                """.trimIndent(),
                id,
            ) { rs -> linkedMapOf(
                "selectionRevisionId" to rs.getObject("selection_revision_id"),
                "customerConsentedAt" to rs.getObject("customer_consented_at"),
                "deliveredAt" to rs.getObject("delivered_at"),
                "deliveryNote" to rs.getString("delivery_note"),
            ) },
        )
    }.filter { (name, rows) ->
        rows.isNotEmpty() || (
            type in USER_NOTIFICATION_CONTEXT_TYPES && name in USER_NOTIFICATION_SECTION_NAMES
        )
    }.toMap(linkedMapOf())

    /** 사용자 알림 조회는 JDBC read model만 사용하며 알림 publisher나 관리자 inbox를 건드리지 않는다. */
    private fun userNotification(rs: ResultSet): Map<String, Any?> = linkedMapOf(
        "id" to rs.getLong("id"),
        "userId" to rs.getLong("user_id"),
        "type" to rs.getString("type"),
        "scope" to rs.getString("scope"),
        "scopeId" to rs.getObject("scope_id"),
        "title" to rs.getString("title"),
        "message" to rs.getString("message"),
        "readAt" to rs.getObject("read_at"),
        "createdAt" to rs.getObject("created_at"),
    )

    private fun userNotificationSettings(
        rs: ResultSet,
        includeNickname: Boolean = false,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "userId" to rs.getLong("user_id"),
        "emailEnabled" to rs.getBoolean("email_enabled"),
        "browserEnabled" to rs.getBoolean("browser_enabled"),
        "settingsPersisted" to rs.getBoolean("persisted"),
        "version" to rs.getLong("version"),
        "updatedAt" to rs.getObject("updated_at"),
    ).apply {
        if (includeNickname) put("nickname", rs.getString("nickname"))
    }

    private fun categoryAssignmentRows(predicate: String, id: Long): List<Map<String, Any?>> = rows(
        """
            SELECT a.photo_id, a.detail_folder_id, d.concept_folder_id, p.original_file_name,
                   a.assigned_by_user_id, a.assigned_source, a.confidence, a.assigned_at, a.version
            FROM photo_category_assignments a
            JOIN detail_folders d ON d.id = a.detail_folder_id
            JOIN photos p ON p.id = a.photo_id
            $predicate
        """.trimIndent(),
        id,
    ) { rs -> linkedMapOf(
        "photoId" to rs.getLong("photo_id"),
        "detailFolderId" to rs.getLong("detail_folder_id"),
        "conceptFolderId" to rs.getLong("concept_folder_id"),
        "fileName" to rs.getString("original_file_name"),
        "assignedByUserId" to rs.getObject("assigned_by_user_id"),
        "assignedSource" to rs.getString("assigned_source"),
        "confidence" to rs.getObject("confidence"),
        "assignedAt" to rs.getObject("assigned_at"),
        "version" to rs.getLong("version"),
    ) }

    /** 관리자 진단에서는 휴지통 사진까지 관계를 보존하고 공유 방식에 맞는 소속만 조회한다. */
    private fun sharedPhotosSql(): String = """
        SELECT a.photo_id, a.assigned_at AS created_at
        FROM collab_sessions s
        JOIN detail_folders d ON d.concept_folder_id = s.concept_folder_id AND d.deleted_at IS NULL
        JOIN photo_category_assignments a ON a.detail_folder_id = d.id
        WHERE s.id = :id
        UNION ALL
        SELECT membership.photo_id, membership.created_at
        FROM collab_sessions s
        JOIN collab_session_photos membership ON membership.collab_session_id = s.id
        WHERE s.id = :id AND s.concept_folder_id IS NULL
    """.trimIndent()

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

    private companion object {
        val USER_NOTIFICATION_CONTEXT_TYPES = setOf(
            AdminResourceType.USER,
            AdminResourceType.STUDIO,
            AdminResourceType.GALLERY,
        )
        val USER_NOTIFICATION_SECTION_NAMES = setOf(
            "userNotifications",
            "userNotificationSettings",
        )
    }

    data class ResourceReference(val type: AdminResourceType, val id: Long)
}
