package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminOperationRecordResponse
import com.soma.wes.admin.resource.dto.AdminOperationalIssueResponse
import com.soma.wes.admin.resource.dto.AdminResourceCountResponse
import com.soma.wes.admin.resource.dto.AdminResourcePageResponse
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.dto.AdminResourceSummaryResponse
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.studio.domain.Studio
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Locale
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Repository
class AdminResourceRepository(
    private val jdbcClient: JdbcClient,
    private val secureTokenGenerator: SecureTokenGenerator,
    private val objectMapper: ObjectMapper,
) {

    fun findPhotoOriginal(photoId: Long): PhotoOriginal? = jdbcClient.sql(
        """
            SELECT id, original_file_name, storage_key, preview_key
            FROM photos
            WHERE id = :photoId AND deleted_at IS NULL
        """.trimIndent(),
    )
        .param("photoId", photoId)
        .query { rs, _ ->
            PhotoOriginal(
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

    fun create(type: AdminResourceType, fields: Map<String, Any?>): ResourceCreateResult {
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
            else -> insertResource(definition, normalized).also { createdId ->
                when (type) {
                    AdminResourceType.USER -> createPersonalWorkspace(createdId, normalized.getValue("nickname").toString())
                    AdminResourceType.GALLERY -> createSelection(createdId)
                    else -> Unit
                }
            }
        }
        return ResourceCreateResult(id)
    }

    private fun insertResource(definition: ResourceDefinition, normalized: Map<String, Any?>): Long {
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
            normalized[definition.generatedSecretField!!] = secureTokenGenerator.generate()
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
            UPDATE photo_category_assignments
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

    /**
     * 감사 스냅샷에서 수정 허용 필드만 복원한다. 식별자·소유 관계·삭제 상태·secret은
     * 스냅샷이 변조되어도 이 경로로 바뀌지 않는다.
     */
    fun restoreRevision(
        type: AdminResourceType,
        id: Long,
        expectedVersion: Long,
        snapshot: JsonNode,
    ): RevisionRestoreResult {
        val definition = definition(type)
        val before = find(type, id) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        if (before.version != expectedVersion) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        if (
            snapshot.path("type").asText() != type.name ||
            !snapshot.path("id").isIntegralNumber ||
            snapshot.path("id").asLong() != id ||
            !snapshot.path("version").isIntegralNumber ||
            !snapshot.path("deleted").isBoolean ||
            snapshot.path("deleted").asBoolean() != before.deleted ||
            before.deleted
        ) {
            throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        }

        RELATION_FIELDS.getValue(type).forEach { relation ->
            val selected = snapshot.get(relation)
                ?: throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
            if (!sameValue(before.fields[relation], jsonValue(selected))) {
                throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
            }
        }

        val restoredFields = definition.fields
            .asSequence()
            .filter { it.updateAllowed && !it.masked }
            .mapNotNull { field ->
                val node = snapshot.get(field.name) ?: return@mapNotNull null
                val value = jsonValue(node)
                if (value == REDACTED || value == MASKED) null else field.name to value
            }
            .filterNot { (name, value) -> sameValue(before.fields[name], value) }
            .toMap()
        if (restoredFields.isEmpty()) throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)

        val updated = try {
            update(type, id, expectedVersion, restoredFields)
        } catch (error: AdminException) {
            if (error.errorCode == AdminErrorCode.RESOURCE_VERSION_CONFLICT) throw error
            throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        }
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        val after = find(type, id) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        return RevisionRestoreResult(before, after)
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
        else -> throw AdminException(AdminErrorCode.RESOURCE_SUSPENSION_UNSUPPORTED)
    }

    fun revokeRefreshToken(userId: Long) {
        jdbcClient.sql("DELETE FROM refresh_tokens WHERE user_id = :userId")
            .param("userId", userId)
            .update()
    }

    private fun normalizeFields(
        definition: ResourceDefinition,
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
        return fields.mapValues { (name, value) -> definition.field(name).normalize(value) }
    }

    private fun jsonValue(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isBoolean -> node.asBoolean()
        node.isIntegralNumber -> node.asLong()
        node.isFloatingPointNumber -> node.asDouble()
        node.isTextual -> node.asText()
        else -> throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
    }

    private fun sameValue(left: Any?, right: Any?): Boolean = when {
        left is Number && right is Number -> left.toLong() == right.toLong()
        left is OffsetDateTime && right is String -> left.toString() == right
        else -> left == right
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

    private fun detail(definition: ResourceDefinition, rs: ResultSet): AdminResourceResponse =
        AdminResourceResponse(
            type = definition.type,
            id = rs.getLong("id"),
            version = rs.getLong("version"),
            label = rs.getString("label"),
            deleted = rs.getBoolean("deleted"),
            fields = definition.fields.associate { field -> field.name to field.read(rs, objectMapper) },
            createdAt = zonedDateTime(rs, "created_at"),
            updatedAt = zonedDateTime(rs, "updated_at"),
        )

    private fun zonedDateTime(rs: ResultSet, column: String): ZonedDateTime? =
        rs.getObject(column, OffsetDateTime::class.java)?.toZonedDateTime()

    private fun definition(type: AdminResourceType): ResourceDefinition = DEFINITIONS.getValue(type)

    private data class ResourceDefinition(
        val type: AdminResourceType,
        val table: String,
        val idColumn: String = "id",
        val labelExpression: String,
        val searchExpression: String,
        val fields: List<FieldDefinition>,
        val softDeleteColumn: String? = null,
        val defaults: Map<String, Any?> = emptyMap(),
        val generatedSecretField: String? = null,
        val createSupported: Boolean = true,
        val updateSupported: Boolean = true,
    ) {
        val summarySql: String = """
            SELECT '${type.name}' AS resource_type,
                   $idColumn AS id,
                   version,
                   COALESCE(($labelExpression)::TEXT, '${type.name.lowercase()} #' || $idColumn) AS label,
                   ${softDeleteColumn?.let { "$it IS NOT NULL" } ?: "FALSE"} AS deleted,
                   created_at,
                   updated_at,
                   CONCAT_WS(' ', $idColumn::TEXT, $searchExpression) AS search_text
            FROM $table
        """.trimIndent()

        val detailSql: String = """
            SELECT $idColumn AS id,
                   version,
                   COALESCE(($labelExpression)::TEXT, '${type.name.lowercase()} #' || $idColumn) AS label,
                   ${softDeleteColumn?.let { "$it IS NOT NULL" } ?: "FALSE"} AS deleted,
                   created_at,
                   updated_at,
                   ${fields.joinToString { it.selectExpression }}
            FROM $table
            WHERE $idColumn = :id
        """.trimIndent()

        fun field(name: String): FieldDefinition =
            fields.find { it.name == name } ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
    }

    private data class FieldDefinition(
        val name: String,
        val column: String,
        val kind: FieldKind,
        val requiredOnCreate: Boolean = false,
        val createAllowed: Boolean = true,
        val updateAllowed: Boolean = true,
        val nullable: Boolean = false,
        val maxLength: Int? = null,
        val minNumber: Long? = null,
        val maxNumber: Long? = null,
        val allowedValues: Set<String> = emptySet(),
        val masked: Boolean = false,
        val readExpression: String? = null,
        val readColumn: String = column,
    ) {
        val selectExpression: String
            get() = readExpression ?: column

        fun read(rs: ResultSet, objectMapper: ObjectMapper): Any? {
            val raw = rs.getObject(readColumn)
            if (masked && raw != null) return MASKED
            return when (kind) {
                FieldKind.REVOKED -> raw != null
                FieldKind.DATE_TIME -> raw?.let { rs.getObject(readColumn, OffsetDateTime::class.java) }
                FieldKind.JSON -> raw?.let {
                    objectMapper.readValue(rs.getString(readColumn), Map::class.java).entries
                        .associate { (key, value) -> key.toString() to value }
                }
                else -> raw
            }
        }

        fun normalize(value: Any?): Any? {
            if (value == null) {
                if (!nullable) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
                return null
            }
            return try {
                when (kind) {
                    FieldKind.STRING -> value.toString().trim().also {
                        if (it.isEmpty() || (maxLength != null && it.length > maxLength)) invalid()
                    }
                    FieldKind.EMAIL -> value.toString().trim().also {
                        if (it.length > (maxLength ?: Int.MAX_VALUE) || !EMAIL.matches(it)) invalid()
                    }
                    FieldKind.GALLERY_URL -> Studio.validateGalleryUrl(value.toString())
                    FieldKind.ENUM -> value.toString().uppercase(Locale.ROOT).also {
                        if (it !in allowedValues) invalid()
                    }
                    FieldKind.LONG -> number(value).toLong().also {
                        if ((minNumber != null && it < minNumber) || (maxNumber != null && it > maxNumber)) invalid()
                    }
                    FieldKind.INT -> number(value).toInt().also {
                        if ((minNumber != null && it < minNumber) || (maxNumber != null && it > maxNumber)) invalid()
                    }
                    FieldKind.DECIMAL -> invalid()
                    FieldKind.BOOLEAN -> when (value) {
                        is Boolean -> value
                        is String -> value.toBooleanStrict()
                        else -> invalid()
                    }
                    FieldKind.DATE_TIME -> OffsetDateTime.parse(value.toString())
                    FieldKind.JSON -> invalid()
                    FieldKind.REVOKED -> when (value) {
                        is Boolean -> if (value) OffsetDateTime.now() else null
                        is String -> if (value.toBooleanStrict()) OffsetDateTime.now() else null
                        else -> invalid()
                    }
                }
            } catch (_: RuntimeException) {
                invalid()
            }
        }

        private fun number(value: Any): Number = value as? Number ?: value.toString().toLong()

        private fun invalid(): Nothing = throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
    }

    private enum class FieldKind { STRING, EMAIL, GALLERY_URL, ENUM, LONG, INT, DECIMAL, BOOLEAN, DATE_TIME, JSON, REVOKED }

    data class ResourceCreateResult(
        val id: Long,
    )

    companion object {
        private const val MASKED = "[MASKED]"
        private const val REDACTED = "[REDACTED]"
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val LEGACY_DIRECT_TRASH_TYPES = setOf(
            AdminResourceType.GALLERY,
            AdminResourceType.PHOTO,
        )
        private val HARD_DELETE_TYPES = setOf(
            AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
            AdminResourceType.PHOTO_RATING,
        )
        private val RELATION_FIELDS = mapOf(
            AdminResourceType.USER to emptySet(),
            AdminResourceType.WORKSPACE to setOf("type", "personalOwnerUserId"),
            AdminResourceType.STUDIO to setOf("workspaceId", "ownerUserId"),
            AdminResourceType.GALLERY to setOf("workspaceId"),
            AdminResourceType.PHOTO to setOf("galleryId"),
            AdminResourceType.CONCEPT_FOLDER to setOf("galleryId"),
            AdminResourceType.DETAIL_FOLDER to setOf("conceptFolderId"),
            AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT to setOf("photoId"),
            AdminResourceType.CATEGORIZATION_JOB to setOf("galleryId", "mode"),
            AdminResourceType.PHOTO_RATING to setOf("photoId"),
            AdminResourceType.SELECTION to setOf("galleryId"),
            AdminResourceType.COLLABORATION to setOf("galleryId", "conceptFolderId"),
            AdminResourceType.RETOUCH_REQUEST to setOf("galleryId"),
        )

        private val DEFINITIONS = listOf(
            ResourceDefinition(
                type = AdminResourceType.USER,
                table = "users",
                labelExpression = "nickname",
                searchExpression = "CONCAT_WS(' ', nickname, email, provider)",
                fields = listOf(
                    FieldDefinition("provider", "provider", FieldKind.ENUM, requiredOnCreate = true, updateAllowed = false, allowedValues = setOf("GOOGLE", "NAVER", "KAKAO")),
                    FieldDefinition("providerId", "provider_id", FieldKind.STRING, requiredOnCreate = true, updateAllowed = false, maxLength = 255, masked = true),
                    FieldDefinition("nickname", "nickname", FieldKind.STRING, requiredOnCreate = true, maxLength = 50),
                    FieldDefinition("email", "email", FieldKind.EMAIL, nullable = true, maxLength = 255),
                    FieldDefinition("suspendedAt", "suspended_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
            ),
            ResourceDefinition(
                type = AdminResourceType.WORKSPACE,
                table = "workspaces",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, type, personal_owner_user_id)",
                fields = listOf(
                    FieldDefinition(
                        "type",
                        "type",
                        FieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("PERSONAL", "STUDIO"),
                    ),
                    FieldDefinition("name", "name", FieldKind.STRING, createAllowed = false, maxLength = 100),
                    FieldDefinition(
                        "personalOwnerUserId",
                        "personal_owner_user_id",
                        FieldKind.LONG,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                        minNumber = 1,
                    ),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                createSupported = false,
            ),
            ResourceDefinition(
                type = AdminResourceType.STUDIO,
                table = "studios",
                idColumn = "workspace_id",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, gallery_url, inflow_channel, contact, description, workspace_id)",
                fields = listOf(
                    FieldDefinition("workspaceId", "workspace_id", FieldKind.LONG, createAllowed = false, updateAllowed = false, minNumber = 1),
                    FieldDefinition(
                        "ownerUserId",
                        "owner_user_id",
                        FieldKind.LONG,
                        requiredOnCreate = true,
                        updateAllowed = false,
                        minNumber = 1,
                        readExpression = "(SELECT wm.user_id FROM workspace_members wm WHERE wm.workspace_id = studios.workspace_id AND wm.role = 'OWNER' AND wm.deleted_at IS NULL ORDER BY wm.id LIMIT 1) AS owner_user_id",
                    ),
                    FieldDefinition("name", "name", FieldKind.STRING, requiredOnCreate = true, maxLength = 255),
                    FieldDefinition("galleryUrl", "gallery_url", FieldKind.GALLERY_URL, requiredOnCreate = true, maxLength = 255),
                    // 기존 유입 경로는 과거 데이터 확인용이며 BackOffice에서 새로 기록하거나 고치지 않는다.
                    FieldDefinition(
                        "inflowChannel",
                        "inflow_channel",
                        FieldKind.STRING,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                        maxLength = 255,
                    ),
                    FieldDefinition("contact", "contact", FieldKind.STRING, nullable = true, maxLength = 100),
                    FieldDefinition("description", "description", FieldKind.STRING, nullable = true, maxLength = 500),
                    FieldDefinition("suspendedAt", "suspended_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("contact" to null, "description" to null),
            ),
            ResourceDefinition(
                type = AdminResourceType.GALLERY,
                table = "galleries",
                labelExpression = "title",
                searchExpression = "CONCAT_WS(' ', title, status, workflow_status, stage, workspace_id)",
                fields = listOf(
                    FieldDefinition("workspaceId", "workspace_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("createdByUserId", "created_by_user_id", FieldKind.LONG, updateAllowed = false, nullable = true, minNumber = 1),
                    FieldDefinition("title", "title", FieldKind.STRING, requiredOnCreate = true, maxLength = 100),
                    // 기존 status는 외부 공개 상태다. 운영 workflowStatus와 의도적으로 분리한다.
                    FieldDefinition(
                        "status",
                        "status",
                        FieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("DRAFT", "OPEN", "CLOSED"),
                    ),
                    FieldDefinition(
                        "workflowStatus",
                        "workflow_status",
                        FieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("DRAFT", "IN_PROGRESS", "COMPLETED", "ARCHIVED"),
                    ),
                    // 제품 workflow만 전이시키는 6단계 화면 상태다. 일반 관리자 CRUD에는 열지 않는다.
                    FieldDefinition(
                        "stage",
                        "stage",
                        FieldKind.ENUM,
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
                    FieldDefinition(
                        "selectionDeadline",
                        "selection_deadline",
                        FieldKind.DATE_TIME,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    FieldDefinition("maxSelectablePhotoCount", "max_selectable_photo_count", FieldKind.INT, nullable = true, minNumber = 1),
                    FieldDefinition("maxRetouchRoundCount", "max_retouch_round_count", FieldKind.INT, nullable = true, minNumber = 1),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
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
            ResourceDefinition(
                type = AdminResourceType.PHOTO,
                table = "photos",
                labelExpression = "original_file_name",
                searchExpression = "CONCAT_WS(' ', original_file_name, status, gallery_id, content_type)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("storageKey", "storage_key", FieldKind.STRING, requiredOnCreate = true, updateAllowed = false, maxLength = 500, masked = true),
                    FieldDefinition("originalFileName", "original_file_name", FieldKind.STRING, requiredOnCreate = true, updateAllowed = false, maxLength = 255),
                    FieldDefinition("contentType", "content_type", FieldKind.STRING, requiredOnCreate = true, updateAllowed = false, maxLength = 100),
                    FieldDefinition("displayOrder", "display_order", FieldKind.INT, minNumber = 0),
                    FieldDefinition("status", "status", FieldKind.ENUM, allowedValues = setOf("PENDING", "UPLOADED")),
                    FieldDefinition("previewKey", "preview_key", FieldKind.STRING, createAllowed = false, updateAllowed = false, nullable = true, masked = true),
                    FieldDefinition("technicalQualityScore", "technical_quality_score", FieldKind.DECIMAL, createAllowed = false, updateAllowed = false, nullable = true),
                    FieldDefinition("technicalQualitySignals", "technical_quality_signals", FieldKind.JSON, createAllowed = false, updateAllowed = false, nullable = true),
                    FieldDefinition("technicalQualityAnalyzedAt", "quality_analyzed_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                    FieldDefinition("uploadUrlExpiresAt", "upload_url_expires_at", FieldKind.DATE_TIME, nullable = true),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("displayOrder" to 0, "status" to "PENDING", "uploadUrlExpiresAt" to null),
            ),
            ResourceDefinition(
                type = AdminResourceType.CONCEPT_FOLDER,
                table = "concept_folders",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, gallery_id, created_source)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("name", "name", FieldKind.STRING, requiredOnCreate = true, maxLength = 100),
                    FieldDefinition("sortOrder", "sort_order", FieldKind.INT, requiredOnCreate = true, minNumber = 0),
                    FieldDefinition(
                        "createdSource",
                        "created_source",
                        FieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("AI", "USER"),
                    ),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("createdSource" to "USER"),
            ),
            ResourceDefinition(
                type = AdminResourceType.DETAIL_FOLDER,
                table = "detail_folders",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, concept_folder_id, created_source)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, createAllowed = false, updateAllowed = false, minNumber = 1),
                    FieldDefinition("conceptFolderId", "concept_folder_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("name", "name", FieldKind.STRING, requiredOnCreate = true, maxLength = 100),
                    FieldDefinition("sortOrder", "sort_order", FieldKind.INT, requiredOnCreate = true, minNumber = 0),
                    FieldDefinition(
                        "createdSource",
                        "created_source",
                        FieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("AI", "USER"),
                    ),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("createdSource" to "USER"),
            ),
            ResourceDefinition(
                type = AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
                table = "photo_category_assignments",
                idColumn = "photo_id",
                labelExpression = "'photo #' || photo_id || ' category'",
                searchExpression = "CONCAT_WS(' ', photo_id, detail_folder_id, assigned_by_user_id, assigned_source)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, createAllowed = false, updateAllowed = false, minNumber = 1),
                    FieldDefinition("photoId", "photo_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("detailFolderId", "detail_folder_id", FieldKind.LONG, requiredOnCreate = true, minNumber = 1),
                    FieldDefinition("assignedByUserId", "assigned_by_user_id", FieldKind.LONG, requiredOnCreate = true, minNumber = 1),
                    FieldDefinition(
                        "assignedSource",
                        "assigned_source",
                        FieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("AI", "USER"),
                    ),
                    FieldDefinition("confidence", "confidence", FieldKind.DECIMAL, createAllowed = false, updateAllowed = false, nullable = true),
                    FieldDefinition("assignedAt", "assigned_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false),
                ),
                defaults = mapOf("assignedSource" to "USER"),
            ),
            ResourceDefinition(
                type = AdminResourceType.CATEGORIZATION_JOB,
                table = "categorization_jobs",
                labelExpression = "'categorization #' || id || ' / ' || mode",
                searchExpression = "CONCAT_WS(' ', gallery_id, mode, status, failure_code)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, createAllowed = false, updateAllowed = false, minNumber = 1),
                    FieldDefinition("mode", "mode", FieldKind.ENUM, createAllowed = false, updateAllowed = false, allowedValues = setOf("INITIAL", "INCREMENTAL")),
                    FieldDefinition("status", "status", FieldKind.ENUM, createAllowed = false, updateAllowed = false, allowedValues = setOf("RUNNING", "SUCCEEDED", "FAILED")),
                    FieldDefinition("startedAt", "started_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false),
                    FieldDefinition("completedAt", "completed_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                    FieldDefinition("failureCode", "failure_code", FieldKind.STRING, createAllowed = false, updateAllowed = false, nullable = true, maxLength = 80),
                ),
                createSupported = false,
                updateSupported = false,
            ),
            ResourceDefinition(
                type = AdminResourceType.PHOTO_RATING,
                table = "photo_ratings",
                idColumn = "photo_id",
                labelExpression = "'photo #' || photo_id || ' / ' || score || ' stars'",
                searchExpression = "CONCAT_WS(' ', photo_id, score, rated_by)",
                fields = listOf(
                    FieldDefinition("photoId", "photo_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("score", "score", FieldKind.INT, requiredOnCreate = true, minNumber = 1, maxNumber = 5),
                    FieldDefinition("ratedByUserId", "rated_by", FieldKind.LONG, requiredOnCreate = true, minNumber = 1),
                ),
            ),
            ResourceDefinition(
                type = AdminResourceType.SELECTION,
                table = "photo_selections",
                labelExpression = "'selection #' || id",
                searchExpression = "CONCAT_WS(' ', gallery_id, status)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition(
                        "status",
                        "status",
                        FieldKind.ENUM,
                        createAllowed = false,
                        updateAllowed = false,
                        allowedValues = setOf("SELECTING", "SUBMITTED"),
                    ),
                    FieldDefinition(
                        "submittedAt",
                        "submitted_at",
                        FieldKind.DATE_TIME,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("status" to "SELECTING", "submittedAt" to null),
                createSupported = false,
            ),
            ResourceDefinition(
                type = AdminResourceType.COLLABORATION,
                table = "collab_sessions",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, gallery_id)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("conceptFolderId", "concept_folder_id", FieldKind.LONG, updateAllowed = false, nullable = true, minNumber = 1),
                    FieldDefinition("name", "name", FieldKind.STRING, requiredOnCreate = true, maxLength = 100),
                    FieldDefinition("collabToken", "collab_token", FieldKind.STRING, createAllowed = false, updateAllowed = false, maxLength = 255, masked = true),
                    FieldDefinition(
                        "revoked",
                        "revoked_at",
                        FieldKind.REVOKED,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    FieldDefinition(
                        "expiresAt",
                        "expires_at",
                        FieldKind.DATE_TIME,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                generatedSecretField = "collabToken",
            ),
            ResourceDefinition(
                type = AdminResourceType.RETOUCH_REQUEST,
                table = "retouch_rounds",
                labelExpression = "'retouch #' || id || ' / round ' || round_no",
                searchExpression = "CONCAT_WS(' ', gallery_id, round_no, status)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("roundNo", "round_no", FieldKind.INT, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition(
                        "status",
                        "status",
                        FieldKind.ENUM,
                        createAllowed = false,
                        allowedValues = setOf("DRAFTING", "REQUESTED", "COMPLETED"),
                    ),
                    FieldDefinition(
                        "requestedAt",
                        "requested_at",
                        FieldKind.DATE_TIME,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    FieldDefinition(
                        "completedAt",
                        "completed_at",
                        FieldKind.DATE_TIME,
                        createAllowed = false,
                        updateAllowed = false,
                        nullable = true,
                    ),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("status" to "DRAFTING", "requestedAt" to null, "completedAt" to null),
            ),
        ).associateBy(ResourceDefinition::type)
    }

    data class PhotoOriginal(
        val id: Long,
        val originalFileName: String,
        val storageKey: String,
        val previewKey: String? = null,
    )

    data class RevisionRestoreResult(
        val before: AdminResourceResponse,
        val after: AdminResourceResponse,
    )
}
