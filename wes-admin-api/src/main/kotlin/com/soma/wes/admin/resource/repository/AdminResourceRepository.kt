package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.response.AdminOperationRecordResponse
import com.soma.wes.admin.resource.dto.response.AdminOperationalIssueResponse
import com.soma.wes.admin.resource.dto.response.AdminResourceCountResponse
import com.soma.wes.admin.resource.dto.response.AdminResourcePageResponse
import com.soma.wes.admin.resource.dto.response.AdminResourceResponse
import com.soma.wes.admin.resource.dto.response.AdminResourceSummaryResponse
import com.soma.wes.admin.resource.repository.projection.AdminPhotoOriginal
import com.soma.wes.admin.resource.repository.projection.AdminResourceCreateResult
import com.soma.wes.admin.resource.repository.projection.AdminResourceDefinition
import com.soma.wes.admin.resource.repository.projection.AdminResourceFieldDefinition
import com.soma.wes.admin.resource.repository.projection.AdminResourceFieldKind
import com.soma.wes.global.SecureTokenGenerator
import java.time.Clock
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Locale
import tools.jackson.databind.ObjectMapper

/**
 * 자원 종류마다 테이블·컬럼 정의(`AdminResourceDefinition`)로 SQL을 만들어 읽고 쓰는 범용 관리자 CRUD라 JDBC — 엔티티마다 repository를 두면 종류마다 같은 코드가 복제된다.
 */
@Repository
class AdminResourceRepository(
    private val jdbcClient: JdbcClient,
    private val secureTokenGenerator: SecureTokenGenerator,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    companion object {
        private val LEGACY_DIRECT_TRASH_TYPES = setOf(
            AdminResourceType.GALLERY,
            AdminResourceType.PHOTO,
        )
        private val HARD_DELETE_TYPES = setOf(
            AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
            AdminResourceType.PHOTO_RATING,
        )
        private val DEFINITIONS = listOf(
            AdminResourceDefinition(
                type = AdminResourceType.USER,
                table = "users",
                labelExpression = "nickname",
                searchExpression = "CONCAT_WS(' ', nickname, email, provider)",
                fields = listOf(
                    AdminResourceFieldDefinition("provider", "provider", AdminResourceFieldKind.ENUM, requiredOnCreate = true, updateAllowed = false, allowedValues = setOf("GOOGLE", "NAVER", "KAKAO")),
                    AdminResourceFieldDefinition("providerId", "provider_id", AdminResourceFieldKind.STRING, requiredOnCreate = true, updateAllowed = false, maxLength = 255),
                    AdminResourceFieldDefinition("nickname", "nickname", AdminResourceFieldKind.STRING, requiredOnCreate = true, maxLength = 50),
                    AdminResourceFieldDefinition("email", "email", AdminResourceFieldKind.EMAIL, nullable = true, maxLength = 255),
                    AdminResourceFieldDefinition("suspendedAt", "suspended_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                    AdminResourceFieldDefinition("deletedAt", "deleted_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
            ),
            AdminResourceDefinition(
                type = AdminResourceType.WORKSPACE,
                table = "workspaces",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, type, personal_owner_user_id)",
                fields = listOf(
                    AdminResourceFieldDefinition(
                        "type",
                        "type",
                        AdminResourceFieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("PERSONAL", "STUDIO"),
                    ),
                    AdminResourceFieldDefinition("name", "name", AdminResourceFieldKind.STRING, createAllowed = false, maxLength = 100),
                    AdminResourceFieldDefinition(
                        "personalOwnerUserId",
                        "personal_owner_user_id",
                        AdminResourceFieldKind.LONG,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                        minNumber = 1,
                    ),
                    AdminResourceFieldDefinition("deletedAt", "deleted_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                createSupported = false,
            ),
            AdminResourceDefinition(
                type = AdminResourceType.STUDIO,
                table = "studios",
                idColumn = "workspace_id",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, gallery_url, inflow_channel, contact, description, workspace_id)",
                fields = listOf(
                    AdminResourceFieldDefinition("workspaceId", "workspace_id", AdminResourceFieldKind.LONG, createAllowed = false, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition(
                        "ownerUserId",
                        "owner_user_id",
                        AdminResourceFieldKind.LONG,
                        requiredOnCreate = true,
                        updateAllowed = false,
                        minNumber = 1,
                        readExpression = "(SELECT wm.user_id FROM workspace_members wm WHERE wm.workspace_id = studios.workspace_id AND wm.role = 'OWNER' AND wm.deleted_at IS NULL ORDER BY wm.id LIMIT 1) AS owner_user_id",
                    ),
                    AdminResourceFieldDefinition("name", "name", AdminResourceFieldKind.STRING, requiredOnCreate = true, maxLength = 255),
                    AdminResourceFieldDefinition("galleryUrl", "gallery_url", AdminResourceFieldKind.GALLERY_URL, requiredOnCreate = true, maxLength = 255),
                    // 기존 유입 경로는 과거 데이터 확인용이며 BackOffice에서 새로 기록하거나 고치지 않는다.
                    AdminResourceFieldDefinition(
                        "inflowChannel",
                        "inflow_channel",
                        AdminResourceFieldKind.STRING,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                        maxLength = 255,
                    ),
                    AdminResourceFieldDefinition("contact", "contact", AdminResourceFieldKind.STRING, nullable = true, maxLength = 100),
                    AdminResourceFieldDefinition("description", "description", AdminResourceFieldKind.STRING, nullable = true, maxLength = 500),
                    AdminResourceFieldDefinition("suspendedAt", "suspended_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                    AdminResourceFieldDefinition("deletedAt", "deleted_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("contact" to null, "description" to null),
            ),
            AdminResourceDefinition(
                type = AdminResourceType.GALLERY,
                table = "galleries",
                labelExpression = "title",
                searchExpression = "CONCAT_WS(' ', title, status, workflow_status, stage, workspace_id)",
                fields = listOf(
                    AdminResourceFieldDefinition("workspaceId", "workspace_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition("createdByUserId", "created_by_user_id", AdminResourceFieldKind.LONG, updateAllowed = false, nullable = true, minNumber = 1),
                    AdminResourceFieldDefinition("title", "title", AdminResourceFieldKind.STRING, requiredOnCreate = true, maxLength = 100),
                    // 기존 status는 외부 공개 상태다. 운영 workflowStatus와 의도적으로 분리한다.
                    AdminResourceFieldDefinition(
                        "status",
                        "status",
                        AdminResourceFieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("DRAFT", "OPEN", "CLOSED"),
                    ),
                    AdminResourceFieldDefinition(
                        "workflowStatus",
                        "workflow_status",
                        AdminResourceFieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("DRAFT", "IN_PROGRESS", "COMPLETED", "ARCHIVED"),
                    ),
                    // 제품 workflow만 전이시키는 6단계 화면 상태다. 일반 관리자 CRUD에는 열지 않는다.
                    AdminResourceFieldDefinition(
                        "stage",
                        "stage",
                        AdminResourceFieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf(
                            "UPLOAD",
                            "SELECTION_IN_PROGRESS",
                            "SELECTION_COMPLETED",
                            "RETOUCH",
                            "DELIVERY",
                            "ARCHIVED",
                        ),
                    ),
                    AdminResourceFieldDefinition(
                        "selectionDeadline",
                        "selection_deadline",
                        AdminResourceFieldKind.DATE_TIME,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    AdminResourceFieldDefinition("maxSelectablePhotoCount", "max_selectable_photo_count", AdminResourceFieldKind.INT, nullable = true, minNumber = 1),
                    AdminResourceFieldDefinition("maxRetouchRoundCount", "max_retouch_round_count", AdminResourceFieldKind.INT, nullable = true, minNumber = 1),
                    AdminResourceFieldDefinition("deletedAt", "deleted_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf(
                    "status" to "DRAFT",
                    "workflowStatus" to "DRAFT",
                    "stage" to "UPLOAD",
                    "selectionDeadline" to null,
                    "maxSelectablePhotoCount" to null,
                    "maxRetouchRoundCount" to null,
                    "createdByUserId" to null,
                ),
            ),
            AdminResourceDefinition(
                type = AdminResourceType.PHOTO,
                table = "photos",
                labelExpression = "original_file_name",
                searchExpression = "CONCAT_WS(' ', original_file_name, status, gallery_id, content_type)",
                fields = listOf(
                    AdminResourceFieldDefinition("galleryId", "gallery_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition("storageKey", "storage_key", AdminResourceFieldKind.STRING, requiredOnCreate = true, updateAllowed = false, maxLength = 500),
                    AdminResourceFieldDefinition("originalFileName", "original_file_name", AdminResourceFieldKind.STRING, requiredOnCreate = true, updateAllowed = false, maxLength = 255),
                    AdminResourceFieldDefinition("contentType", "content_type", AdminResourceFieldKind.STRING, requiredOnCreate = true, updateAllowed = false, maxLength = 100),
                    AdminResourceFieldDefinition("displayOrder", "display_order", AdminResourceFieldKind.INT, minNumber = 0),
                    AdminResourceFieldDefinition("status", "status", AdminResourceFieldKind.ENUM, allowedValues = setOf("PENDING", "UPLOADED")),
                    AdminResourceFieldDefinition("previewKey", "preview_key", AdminResourceFieldKind.STRING, createAllowed = false, updateAllowed = false, nullable = true),
                    AdminResourceFieldDefinition("uploadUrlExpiresAt", "upload_url_expires_at", AdminResourceFieldKind.DATE_TIME, nullable = true),
                    AdminResourceFieldDefinition("deletedAt", "deleted_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("displayOrder" to 0, "status" to "PENDING", "uploadUrlExpiresAt" to null),
            ),
            AdminResourceDefinition(
                type = AdminResourceType.CONCEPT_FOLDER,
                table = "concept_folders",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, gallery_id, created_source)",
                fields = listOf(
                    AdminResourceFieldDefinition("galleryId", "gallery_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition("name", "name", AdminResourceFieldKind.STRING, requiredOnCreate = true, maxLength = 100),
                    AdminResourceFieldDefinition("sortOrder", "sort_order", AdminResourceFieldKind.INT, requiredOnCreate = true, minNumber = 0),
                    AdminResourceFieldDefinition(
                        "createdSource",
                        "created_source",
                        AdminResourceFieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("AI", "USER"),
                    ),
                    AdminResourceFieldDefinition("deletedAt", "deleted_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("createdSource" to "USER"),
            ),
            AdminResourceDefinition(
                type = AdminResourceType.DETAIL_FOLDER,
                table = "detail_folders",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, concept_folder_id, created_source)",
                fields = listOf(
                    AdminResourceFieldDefinition("galleryId", "gallery_id", AdminResourceFieldKind.LONG, createAllowed = false, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition("conceptFolderId", "concept_folder_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition("name", "name", AdminResourceFieldKind.STRING, requiredOnCreate = true, maxLength = 100),
                    AdminResourceFieldDefinition("sortOrder", "sort_order", AdminResourceFieldKind.INT, requiredOnCreate = true, minNumber = 0),
                    AdminResourceFieldDefinition(
                        "createdSource",
                        "created_source",
                        AdminResourceFieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("AI", "USER"),
                    ),
                    AdminResourceFieldDefinition("deletedAt", "deleted_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("createdSource" to "USER"),
            ),
            AdminResourceDefinition(
                type = AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
                table = "detail_folder_assignments",
                idColumn = "photo_id",
                labelExpression = "'photo #' || photo_id || ' category'",
                searchExpression = "CONCAT_WS(' ', photo_id, detail_folder_id, assigned_by_user_id, assigned_source)",
                fields = listOf(
                    AdminResourceFieldDefinition("galleryId", "gallery_id", AdminResourceFieldKind.LONG, createAllowed = false, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition("photoId", "photo_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition("detailFolderId", "detail_folder_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, minNumber = 1),
                    AdminResourceFieldDefinition("assignedByUserId", "assigned_by_user_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, minNumber = 1),
                    AdminResourceFieldDefinition(
                        "assignedSource",
                        "assigned_source",
                        AdminResourceFieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("AI", "USER"),
                    ),
                    AdminResourceFieldDefinition("confidence", "confidence", AdminResourceFieldKind.DECIMAL, createAllowed = false, updateAllowed = false, nullable = true),
                    AdminResourceFieldDefinition("assignedAt", "assigned_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false),
                ),
                defaults = mapOf("assignedSource" to "USER"),
            ),
            AdminResourceDefinition(
                type = AdminResourceType.PHOTO_RATING,
                table = "photo_ratings",
                idColumn = "photo_id",
                labelExpression = "'photo #' || photo_id || ' / ' || score || ' stars'",
                searchExpression = "CONCAT_WS(' ', photo_id, score, rated_by)",
                fields = listOf(
                    AdminResourceFieldDefinition("photoId", "photo_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition("score", "score", AdminResourceFieldKind.INT, requiredOnCreate = true, minNumber = 1, maxNumber = 5),
                    AdminResourceFieldDefinition("ratedByUserId", "rated_by", AdminResourceFieldKind.LONG, requiredOnCreate = true, minNumber = 1),
                ),
            ),
            AdminResourceDefinition(
                type = AdminResourceType.SELECTION,
                table = "photo_selections",
                labelExpression = "'selection #' || id",
                searchExpression = "CONCAT_WS(' ', gallery_id, status)",
                fields = listOf(
                    AdminResourceFieldDefinition("galleryId", "gallery_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition(
                        "status",
                        "status",
                        AdminResourceFieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("SELECTING", "SUBMITTED"),
                    ),
                    AdminResourceFieldDefinition(
                        "submittedAt",
                        "submitted_at",
                        AdminResourceFieldKind.DATE_TIME,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    AdminResourceFieldDefinition("deletedAt", "deleted_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("status" to "SELECTING", "submittedAt" to null),
                createSupported = false,
            ),
            AdminResourceDefinition(
                type = AdminResourceType.COLLABORATION,
                table = "collab_sessions",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, gallery_id)",
                fields = listOf(
                    AdminResourceFieldDefinition("galleryId", "gallery_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition("name", "name", AdminResourceFieldKind.STRING, requiredOnCreate = true, maxLength = 100),
                    AdminResourceFieldDefinition("collabToken", "collab_token", AdminResourceFieldKind.STRING, createAllowed = false, updateAllowed = false, maxLength = 255),
                    AdminResourceFieldDefinition(
                        "revoked",
                        "revoked_at",
                        AdminResourceFieldKind.REVOKED,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    AdminResourceFieldDefinition(
                        "expiresAt",
                        "expires_at",
                        AdminResourceFieldKind.DATE_TIME,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    AdminResourceFieldDefinition("deletedAt", "deleted_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                generatedSecretField = "collabToken",
            ),
            AdminResourceDefinition(
                type = AdminResourceType.RETOUCH_REQUEST,
                table = "retouch_rounds",
                labelExpression = "'retouch #' || id || ' / round ' || round_no",
                searchExpression = "CONCAT_WS(' ', gallery_id, round_no, status)",
                fields = listOf(
                    AdminResourceFieldDefinition("galleryId", "gallery_id", AdminResourceFieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition("roundNo", "round_no", AdminResourceFieldKind.INT, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    AdminResourceFieldDefinition(
                        "status",
                        "status",
                        AdminResourceFieldKind.ENUM,
                        createAllowed = false,
                        allowedValues = setOf("DRAFTING", "REQUESTED", "COMPLETED"),
                    ),
                    AdminResourceFieldDefinition(
                        "requestedAt",
                        "requested_at",
                        AdminResourceFieldKind.DATE_TIME,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    AdminResourceFieldDefinition(
                        "completedAt",
                        "completed_at",
                        AdminResourceFieldKind.DATE_TIME,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    AdminResourceFieldDefinition("deletedAt", "deleted_at", AdminResourceFieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("status" to "DRAFTING", "requestedAt" to null, "completedAt" to null),
            ),
        ).associateBy(AdminResourceDefinition::type)
    }

    fun findPhotoOriginal(photoId: Long): AdminPhotoOriginal? = jdbcClient.sql(
        """
            SELECT id, original_file_name, storage_key, preview_key
            FROM photos
            WHERE id = :photoId AND deleted_at IS NULL
        """.trimIndent(),
    )
        .param("photoId", photoId)
        .query { rs, _ ->
            AdminPhotoOriginal(
                id = rs.getLong("id"),
                originalFileName = rs.getString("original_file_name"),
                storageKey = rs.getString("storage_key"),
                previewKey = rs.getString("preview_key"),
            )
        }
        .optional()
        .orElse(null)

    fun countAll(): Map<AdminResourceType, AdminResourceCountResponse> =
        AdminResourceType.entries.associateWith { type ->
            val definition = definition(type)
            val deletedExpression = definition.softDeleteColumn?.let { "$it IS NOT NULL" } ?: "FALSE"
            jdbcClient.sql(
                """
                    SELECT COUNT(*) FILTER (WHERE NOT ($deletedExpression)) AS active_count,
                           COUNT(*) FILTER (WHERE $deletedExpression) AS deleted_count,
                           COUNT(*) AS total_count
                    FROM ${definition.table}
                """.trimIndent(),
            ).query { rs, _ ->
                AdminResourceCountResponse(
                    active = rs.getLong("active_count"),
                    deleted = rs.getLong("deleted_count"),
                    total = rs.getLong("total_count"),
                )
            }.single()
        }

    fun findOperationalIssues(): List<AdminOperationalIssueResponse> = listOf(
        issue(
            code = "PHOTO_UPLOAD_STALLED",
            label = "30분 넘게 업로드 대기 중인 사진",
            severity = "ERROR",
            resourceType = AdminResourceType.PHOTO,
            sql = """
                SELECT COUNT(*) FROM photos
                WHERE deleted_at IS NULL
                  AND status = 'PENDING'
                  AND created_at < CURRENT_TIMESTAMP - INTERVAL '30 minutes'
            """.trimIndent(),
        ),
        issue(
            code = "PHOTO_EMBEDDING_MISSING",
            label = "AI 분석 결과가 없는 업로드 사진",
            severity = "WARNING",
            resourceType = AdminResourceType.PHOTO,
            sql = """
                SELECT COUNT(*) FROM photos p
                WHERE p.deleted_at IS NULL
                  AND p.status = 'UPLOADED'
                  AND NOT EXISTS (SELECT 1 FROM photo_analysis a WHERE a.photo_id = p.id AND a.embedding IS NOT NULL)
            """.trimIndent(),
        ),
        issue(
            code = "GALLERY_INVITE_EXPIRED",
            label = "폐기되지 않은 만료 초대",
            severity = "WARNING",
            resourceType = AdminResourceType.GALLERY,
            sql = """
                SELECT COUNT(*) FROM gallery_invites
                WHERE revoked_at IS NULL
                  AND expires_at < CURRENT_TIMESTAMP
            """.trimIndent(),
        ),
        issue(
            code = "ADMIN_REPROCESS_FAILED",
            label = "실패한 관리자 재처리 요청",
            severity = "ERROR",
            resourceType = AdminResourceType.GALLERY,
            sql = "SELECT COUNT(*) FROM admin_idempotency_keys WHERE status = 'FAILED'",
        ),
    )

    fun findRecentFailedOperations(): List<AdminOperationRecordResponse> = jdbcClient.sql(
        """
            SELECT id, action, status, target_type, target_id, failure_code, correlation_id,
                   attempt_count, created_at, updated_at
            FROM admin_idempotency_keys
            WHERE status = 'FAILED'
            ORDER BY updated_at DESC, id DESC
            LIMIT 8
        """.trimIndent(),
    ).query { rs, _ ->
        AdminOperationRecordResponse(
            id = rs.getLong("id"),
            action = rs.getString("action"),
            status = rs.getString("status"),
            targetType = rs.getString("target_type")
                ?.let { runCatching { AdminResourceType.valueOf(it) }.getOrNull() },
            targetId = rs.getString("target_id"),
            failureCode = rs.getString("failure_code"),
            correlationId = rs.getString("correlation_id"),
            attemptCount = rs.getInt("attempt_count"),
            createdAt = zonedDateTime(rs, "created_at"),
            updatedAt = zonedDateTime(rs, "updated_at"),
        )
    }.list()

    fun countTrashPending(): Long = jdbcClient.sql(
        """
            SELECT
                (SELECT COUNT(*) FROM admin_trash_batches WHERE status IN ('ACTIVE', 'PURGING')) +
                (SELECT COUNT(*) FROM admin_child_trash_records WHERE status IN ('ACTIVE', 'PURGING')) +
                (SELECT COUNT(*) FROM galleries g
                 WHERE g.deleted_at IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM admin_trash_entries e
                       JOIN admin_trash_batches b ON b.id = e.batch_id AND b.status IN ('ACTIVE', 'PURGING')
                       WHERE e.resource_type = 'GALLERY' AND e.resource_id = g.id
                   )) +
                (SELECT COUNT(*) FROM photos p
                 WHERE p.deleted_at IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM galleries g WHERE g.id = p.gallery_id AND g.deleted_at IS NOT NULL)
                   AND NOT EXISTS (
                       SELECT 1 FROM admin_trash_entries e
                       JOIN admin_trash_batches b ON b.id = e.batch_id AND b.status IN ('ACTIVE', 'PURGING')
                       WHERE e.resource_type = 'PHOTO' AND e.resource_id = p.id
                   ))
        """.trimIndent(),
    ).query { rs, _ -> rs.getLong(1) }.single()

    private fun issue(
        code: String,
        label: String,
        severity: String,
        resourceType: AdminResourceType?,
        sql: String,
    ): AdminOperationalIssueResponse = AdminOperationalIssueResponse(
        code = code,
        label = label,
        count = jdbcClient.sql(sql).query { rs, _ -> rs.getLong(1) }.single(),
        severity = severity,
        resourceType = resourceType,
    )

    fun search(
        query: String?,
        types: Set<AdminResourceType>,
        page: Int,
        size: Int,
    ): AdminResourcePageResponse {
        val selected = (types.ifEmpty { AdminResourceType.entries.toSet() }).map(::definition)
        val normalizedQuery = query?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
        val union = selected.joinToString(" UNION ALL ") { it.summarySql }
        val where = if (normalizedQuery == null) "" else " WHERE LOWER(search_text) LIKE :query"
        val countSql = "SELECT COUNT(*) FROM ($union) resources$where"
        val dataSql = """
            SELECT resource_type, id, version, label, deleted, created_at, updated_at
            FROM ($union) resources
            $where
            ORDER BY updated_at DESC NULLS LAST, resource_type, id DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val countStatement = jdbcClient.sql(countSql)
        val dataStatement = jdbcClient.sql(dataSql)
            .param("limit", size)
            .param("offset", page.toLong() * size)
        if (normalizedQuery != null) {
            countStatement.param("query", "%$normalizedQuery%")
            dataStatement.param("query", "%$normalizedQuery%")
        }

        val totalCount = countStatement.query { rs, _ -> rs.getLong(1) }.single()
        val contents = dataStatement.query { rs, _ -> summary(rs) }.list()
        return AdminResourcePageResponse(
            page = page,
            size = size,
            totalCount = totalCount,
            hasNext = (page.toLong() + 1) * size < totalCount,
            contents = contents,
        )
    }

    fun find(type: AdminResourceType, id: Long): AdminResourceResponse? {
        val definition = definition(type)
        return jdbcClient.sql(definition.detailSql)
            .param("id", id)
            .query { rs, _ -> detail(definition, rs) }
            .optional()
            .orElse(null)
    }

    fun create(type: AdminResourceType, fields: Map<String, Any?>): AdminResourceCreateResult {
        val definition = definition(type)
        if (!definition.createSupported) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val normalized = normalizeFields(definition, fields, creating = true).toMutableMap()
        definition.defaults.forEach { (name, value) -> normalized.putIfAbsent(name, value) }
        definition.generatedSecretField?.let { normalized[it] = secureTokenGenerator.generate() }

        if (type == AdminResourceType.DETAIL_FOLDER) {
            normalized["galleryId"] = galleryIdForConceptFolder(
                (normalized.getValue("conceptFolderId") as Number).toLong(),
            )
        }
        if (type == AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT) {
            validateCategoryAssignment(normalized)
            normalized["galleryId"] = galleryIdForPhoto(
                (normalized.getValue("photoId") as Number).toLong(),
            )
        }

        val id = when (type) {
            AdminResourceType.STUDIO -> createStudio(normalized)
            AdminResourceType.USER,
            AdminResourceType.WORKSPACE,
            AdminResourceType.GALLERY,
            AdminResourceType.PHOTO,
            AdminResourceType.CONCEPT_FOLDER,
            AdminResourceType.DETAIL_FOLDER,
            AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
            AdminResourceType.PHOTO_RATING,
            AdminResourceType.SELECTION,
            AdminResourceType.COLLABORATION,
            AdminResourceType.RETOUCH_REQUEST,
            -> insertResource(definition, normalized).also { createdId ->
                when (type) {
                    AdminResourceType.USER -> createPersonalWorkspace(createdId, normalized.getValue("nickname").toString())
                    AdminResourceType.GALLERY -> createSelection(createdId)
                    AdminResourceType.WORKSPACE,
                    AdminResourceType.STUDIO,
                    AdminResourceType.PHOTO,
                    AdminResourceType.CONCEPT_FOLDER,
                    AdminResourceType.DETAIL_FOLDER,
                    AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
                    AdminResourceType.PHOTO_RATING,
                    AdminResourceType.SELECTION,
                    AdminResourceType.COLLABORATION,
                    AdminResourceType.RETOUCH_REQUEST,
                    -> Unit
                }
            }
        }
        return AdminResourceCreateResult(id)
    }

    private fun insertResource(definition: AdminResourceDefinition, normalized: Map<String, Any?>): Long {
        val columns = normalized.keys.map { definition.field(it).column }
        val parameters = normalized.keys.map { ":$it" }
        val sql = """
            INSERT INTO ${definition.table} (${columns.joinToString()}, version, created_at, updated_at)
            VALUES (${parameters.joinToString()}, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING ${definition.idColumn} AS id
        """.trimIndent()
        var statement = jdbcClient.sql(sql)
        normalized.forEach { (name, value) -> statement = statement.param(name, value) }
        return statement.query { rs, _ -> rs.getLong("id") }.single()
    }

    private fun createPersonalWorkspace(userId: Long, nickname: String) {
        val workspaceId = jdbcClient.sql(
            """
            INSERT INTO workspaces
                (type, name, personal_owner_user_id, version, created_at, updated_at)
            VALUES ('PERSONAL', :name, :userId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("name", "${nickname}의 작업공간")
            .param("userId", userId)
            .query { rs, _ -> rs.getLong("id") }
            .single()
        jdbcClient.sql(
            """
            INSERT INTO workspace_members
                (workspace_id, user_id, role, version, created_at, updated_at)
            VALUES (:workspaceId, :userId, 'OWNER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("workspaceId", workspaceId).param("userId", userId).update()
    }

    private fun createSelection(galleryId: Long) {
        jdbcClient.sql(
            """
            INSERT INTO photo_selections
                (gallery_id, status, version, created_at, updated_at)
            VALUES (:galleryId, 'SELECTING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (gallery_id) DO NOTHING
            """.trimIndent(),
        ).param("galleryId", galleryId).update()
    }

    private fun createStudio(normalized: Map<String, Any?>): Long {
        val ownerUserId = (normalized.getValue("ownerUserId") as Number).toLong()
        val userExists = jdbcClient.sql(
            "SELECT COUNT(*) FROM users WHERE id = :userId AND deleted_at IS NULL",
        ).param("userId", ownerUserId).query { rs, _ -> rs.getLong(1) }.single()
        if (userExists != 1L) throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)

        val workspaceId = jdbcClient.sql(
            """
            INSERT INTO workspaces (type, name, version, created_at, updated_at)
            VALUES ('STUDIO', :name, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("name", normalized.getValue("name"))
            .query { rs, _ -> rs.getLong("id") }.single()
        jdbcClient.sql(
            """
            INSERT INTO workspace_members
                (workspace_id, user_id, role, version, created_at, updated_at)
            VALUES (:workspaceId, :userId, 'OWNER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("workspaceId", workspaceId).param("userId", ownerUserId).update()
        jdbcClient.sql(
            """
            INSERT INTO studios
                (workspace_id, name, gallery_url, contact, description, version, created_at, updated_at)
            VALUES (:workspaceId, :name, :galleryUrl, :contact, :description, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        )
            .param("workspaceId", workspaceId)
            .param("name", normalized.getValue("name"))
            .param("galleryUrl", normalized.getValue("galleryUrl"))
            .param("contact", normalized["contact"])
            .param("description", normalized["description"])
            .update()
        return workspaceId
    }

    fun update(
        type: AdminResourceType,
        id: Long,
        expectedVersion: Long,
        fields: Map<String, Any?>,
    ): Int {
        val definition = definition(type)
        if (!definition.updateSupported) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val normalized = normalizeFields(definition, fields, creating = false).toMutableMap()
        if (type == AdminResourceType.COLLABORATION && fields["revoked"] == false) {
            val secretField = checkNotNull(definition.generatedSecretField) { "공유폴더 정의에 링크 토큰 필드가 없다" }
            normalized[secretField] = secureTokenGenerator.generate()
        }
        if (normalized.isEmpty()) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        if (type == AdminResourceType.RETOUCH_REQUEST && normalized.keys == setOf("status")) {
            return transitionRetouchRound(id, expectedVersion, normalized.getValue("status").toString())
        }
        if (type == AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT) {
            return updateCategoryAssignment(id, expectedVersion, normalized)
        }

        val assignments = normalized.keys.joinToString { name -> "${definition.field(name).column} = :$name" }
        val sql = """
            UPDATE ${definition.table}
            SET $assignments,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE ${definition.idColumn} = :id AND version = :expectedVersion
        """.trimIndent()
        var statement = jdbcClient.sql(sql)
            .param("id", id)
            .param("expectedVersion", expectedVersion)
        normalized.forEach { (name, value) -> statement = statement.param(name, value) }
        return statement.update()
    }

    /** 제품 도메인의 DRAFTING -> REQUESTED -> COMPLETED 전이와 시각 불변식을 그대로 지킨다. */
    private fun transitionRetouchRound(id: Long, expectedVersion: Long, targetStatus: String): Int {
        if (targetStatus !in setOf("REQUESTED", "COMPLETED")) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val updated = jdbcClient.sql(
            """
                UPDATE retouch_rounds
                SET status = :targetStatus,
                    requested_at = CASE
                        WHEN :targetStatus = 'REQUESTED' THEN CURRENT_TIMESTAMP
                        ELSE requested_at
                    END,
                    completed_at = CASE
                        WHEN :targetStatus = 'COMPLETED' THEN CURRENT_TIMESTAMP
                        ELSE NULL
                    END,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                  AND version = :expectedVersion
                  AND deleted_at IS NULL
                  AND (
                    (status = 'DRAFTING' AND :targetStatus = 'REQUESTED') OR
                    (status = 'REQUESTED' AND :targetStatus = 'COMPLETED')
                  )
            """.trimIndent(),
        )
            .param("targetStatus", targetStatus)
            .param("id", id)
            .param("expectedVersion", expectedVersion)
            .update()
        if (updated == 0) {
            val currentVersion = jdbcClient.sql(
                "SELECT version FROM retouch_rounds WHERE id = :id AND deleted_at IS NULL",
            )
                .param("id", id)
                .query { rs, _ -> rs.getLong("version") }
                .optional()
                .orElse(null)
            if (currentVersion == expectedVersion) {
                throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            }
        }
        return updated
    }

    /** 사진과 세부폴더가 같은 활성 갤러리에 속하고 작업자 신원이 실제 사용자일 때만 배정한다. */
    private fun validateCategoryAssignment(fields: Map<String, Any?>) {
        val photoId = (fields.getValue("photoId") as Number).toLong()
        val detailFolderId = (fields.getValue("detailFolderId") as Number).toLong()
        val assignedByUserId = (fields.getValue("assignedByUserId") as Number).toLong()
        val valid = jdbcClient.sql(
            """
            SELECT EXISTS (
                SELECT 1
                FROM photos p
                JOIN detail_folders d ON d.id = :detailFolderId AND d.deleted_at IS NULL
                JOIN concept_folders c ON c.id = d.concept_folder_id AND c.deleted_at IS NULL
                JOIN users u ON u.id = :assignedByUserId AND u.deleted_at IS NULL
                WHERE p.id = :photoId AND p.deleted_at IS NULL AND p.gallery_id = c.gallery_id
            )
            """.trimIndent(),
        )
            .param("photoId", photoId)
            .param("detailFolderId", detailFolderId)
            .param("assignedByUserId", assignedByUserId)
            .query { rs, _ -> rs.getBoolean(1) }
            .single()
        if (!valid) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
    }

    private fun galleryIdForConceptFolder(conceptFolderId: Long): Long = jdbcClient.sql(
        "SELECT gallery_id FROM concept_folders WHERE id = :id AND deleted_at IS NULL",
    )
        .param("id", conceptFolderId)
        .query { rs, _ -> rs.getLong("gallery_id") }
        .optional()
        .orElseThrow { AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS) }

    private fun galleryIdForPhoto(photoId: Long): Long = jdbcClient.sql(
        "SELECT gallery_id FROM photos WHERE id = :id AND deleted_at IS NULL",
    )
        .param("id", photoId)
        .query { rs, _ -> rs.getLong("gallery_id") }
        .optional()
        .orElseThrow { AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS) }

    private fun updateCategoryAssignment(
        photoId: Long,
        expectedVersion: Long,
        fields: Map<String, Any?>,
    ): Int {
        if (fields.keys.any { it !in setOf("detailFolderId", "assignedByUserId") }) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val current = find(AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT, photoId)
            ?: return 0
        val assignedByUserId = fields["assignedByUserId"]
            ?: current.fields["assignedByUserId"]
            ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        val desired = mapOf(
            "photoId" to photoId,
            "detailFolderId" to (fields["detailFolderId"] ?: current.fields.getValue("detailFolderId")),
            "assignedByUserId" to assignedByUserId,
        )
        validateCategoryAssignment(desired)
        return jdbcClient.sql(
            """
            UPDATE detail_folder_assignments
            SET detail_folder_id = :detailFolderId,
                assigned_by_user_id = :assignedByUserId,
                assigned_source = 'USER',
                confidence = NULL,
                assigned_at = CURRENT_TIMESTAMP,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE photo_id = :photoId AND version = :expectedVersion
            """.trimIndent(),
        )
            .param("detailFolderId", desired.getValue("detailFolderId"))
            .param("assignedByUserId", desired.getValue("assignedByUserId"))
            .param("photoId", photoId)
            .param("expectedVersion", expectedVersion)
            .update()
    }

    fun delete(type: AdminResourceType, id: Long, expectedVersion: Long): Int {
        if (type in HARD_DELETE_TYPES) {
            val definition = definition(type)
            return jdbcClient.sql(
                "DELETE FROM ${definition.table} WHERE ${definition.idColumn} = :id AND version = :expectedVersion",
            )
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update()
        }
        if (type !in LEGACY_DIRECT_TRASH_TYPES) {
            throw AdminException(AdminErrorCode.RESOURCE_DELETE_UNSUPPORTED)
        }
        val definition = definition(type)
        val deletedColumn = definition.softDeleteColumn
            ?: throw AdminException(AdminErrorCode.RESOURCE_DELETE_UNSUPPORTED)
        return jdbcClient.sql(
            """
                UPDATE ${definition.table}
                SET $deletedColumn = CURRENT_TIMESTAMP,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                  AND version = :expectedVersion
                  AND $deletedColumn IS NULL
            """.trimIndent(),
        )
            .param("id", id)
            .param("expectedVersion", expectedVersion)
            .update()
    }

    fun restore(type: AdminResourceType, id: Long, expectedVersion: Long): Int {
        if (type !in LEGACY_DIRECT_TRASH_TYPES) {
            throw AdminException(AdminErrorCode.RESOURCE_RESTORE_UNSUPPORTED)
        }
        val definition = definition(type)
        val deletedColumn = definition.softDeleteColumn
            ?: throw AdminException(AdminErrorCode.RESOURCE_RESTORE_UNSUPPORTED)
        return jdbcClient.sql(
            """
                UPDATE ${definition.table}
                SET $deletedColumn = NULL,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                  AND version = :expectedVersion
                  AND $deletedColumn IS NOT NULL
            """.trimIndent(),
        )
            .param("id", id)
            .param("expectedVersion", expectedVersion)
            .update()
    }

    fun setSuspended(type: AdminResourceType, id: Long, expectedVersion: Long, suspended: Boolean): Int {
        if (type !in setOf(AdminResourceType.USER, AdminResourceType.STUDIO)) {
            throw AdminException(AdminErrorCode.RESOURCE_SUSPENSION_UNSUPPORTED)
        }
        val definition = definition(type)
        val currentPredicate = if (suspended) "suspended_at IS NULL" else "suspended_at IS NOT NULL"
        val value = if (suspended) "CURRENT_TIMESTAMP" else "NULL"
        return jdbcClient.sql(
            """
            UPDATE ${definition.table}
            SET suspended_at = $value,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE ${definition.idColumn} = :id AND version = :expectedVersion
              AND deleted_at IS NULL AND $currentPredicate
            """.trimIndent(),
        )
            .param("id", id)
            .param("expectedVersion", expectedVersion)
            .update()
    }

    fun affectedUserIds(type: AdminResourceType, id: Long): List<Long> = when (type) {
        AdminResourceType.USER -> listOf(id)
        AdminResourceType.STUDIO -> jdbcClient.sql(
            """
            SELECT user_id FROM workspace_members
            WHERE workspace_id = :id AND deleted_at IS NULL
            ORDER BY id
            """.trimIndent(),
        ).param("id", id).query { rs, _ -> rs.getLong("user_id") }.list()
        AdminResourceType.WORKSPACE,
        AdminResourceType.GALLERY,
        AdminResourceType.PHOTO,
        AdminResourceType.CONCEPT_FOLDER,
        AdminResourceType.DETAIL_FOLDER,
        AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
        AdminResourceType.PHOTO_RATING,
        AdminResourceType.SELECTION,
        AdminResourceType.COLLABORATION,
        AdminResourceType.RETOUCH_REQUEST,
        -> throw AdminException(AdminErrorCode.RESOURCE_SUSPENSION_UNSUPPORTED)
    }

    fun revokeRefreshToken(userId: Long) {
        jdbcClient.sql("DELETE FROM refresh_tokens WHERE user_id = :userId")
            .param("userId", userId)
            .update()
    }

    private fun normalizeFields(
        definition: AdminResourceDefinition,
        fields: Map<String, Any?>,
        creating: Boolean,
    ): Map<String, Any?> {
        if (fields.keys.any { key -> definition.fields.none { it.name == key } }) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        if (fields.keys.any { key -> if (creating) !definition.field(key).createAllowed else !definition.field(key).updateAllowed }) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        if (creating && definition.fields.any { it.requiredOnCreate && !fields.containsKey(it.name) }) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val now = OffsetDateTime.now(clock)
        return fields.mapValues { (name, value) -> definition.field(name).normalize(value, now) }
    }

    private fun summary(rs: ResultSet): AdminResourceSummaryResponse =
        AdminResourceSummaryResponse(
            type = AdminResourceType.valueOf(rs.getString("resource_type")),
            id = rs.getLong("id"),
            version = rs.getLong("version"),
            label = rs.getString("label"),
            deleted = rs.getBoolean("deleted"),
            createdAt = zonedDateTime(rs, "created_at"),
            updatedAt = zonedDateTime(rs, "updated_at"),
        )

    private fun detail(definition: AdminResourceDefinition, rs: ResultSet): AdminResourceResponse =
        AdminResourceResponse(
            type = definition.type,
            id = rs.getLong("id"),
            version = rs.getLong("version"),
            label = rs.getString("label"),
            deleted = rs.getBoolean("deleted"),
            fields = definition.responseFields.associate { field -> field.name to field.read(rs, objectMapper) },
            createdAt = zonedDateTime(rs, "created_at"),
            updatedAt = zonedDateTime(rs, "updated_at"),
        )

    private fun zonedDateTime(rs: ResultSet, column: String): ZonedDateTime? =
        rs.getObject(column, OffsetDateTime::class.java)?.toZonedDateTime()

    private fun definition(type: AdminResourceType): AdminResourceDefinition = DEFINITIONS.getValue(type)

}
