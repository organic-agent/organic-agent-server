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
import com.soma.wes.user.domain.UserType
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
        val normalized = normalizeFields(definition, fields, creating = true).toMutableMap()
        definition.defaults.forEach { (name, value) -> normalized.putIfAbsent(name, value) }
        definition.generatedSecretField?.let { normalized[it] = secureTokenGenerator.generate() }
        val userTypeChange = if (type == AdminResourceType.STUDIO) {
            confirmUserType((normalized.getValue("userId") as Number).toLong(), UserType.PHOTOGRAPHER)
        } else null

        val columns = normalized.keys.map { definition.field(it).column }
        val parameters = normalized.keys.map { ":$it" }
        val sql = """
            INSERT INTO ${definition.table} (${columns.joinToString()}, version, created_at, updated_at)
            VALUES (${parameters.joinToString()}, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
        """.trimIndent()
        var statement = jdbcClient.sql(sql)
        normalized.forEach { (name, value) -> statement = statement.param(name, value) }
        val id = statement.query { rs, _ -> rs.getLong("id") }.single()
        return ResourceCreateResult(id, userTypeChange)
    }

    fun update(
        type: AdminResourceType,
        id: Long,
        expectedVersion: Long,
        fields: Map<String, Any?>,
    ): Int {
        val definition = definition(type)
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

        val assignments = normalized.keys.joinToString { name -> "${definition.field(name).column} = :$name" }
        val sql = """
            UPDATE ${definition.table}
            SET $assignments,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND version = :expectedVersion
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
        val table = when (type) {
            AdminResourceType.USER -> "users"
            AdminResourceType.STUDIO -> "studios"
            else -> throw AdminException(AdminErrorCode.RESOURCE_SUSPENSION_UNSUPPORTED)
        }
        val currentPredicate = if (suspended) "suspended_at IS NULL" else "suspended_at IS NOT NULL"
        val value = if (suspended) "CURRENT_TIMESTAMP" else "NULL"
        return jdbcClient.sql(
            """
            UPDATE $table
            SET suspended_at = $value,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND version = :expectedVersion
              AND deleted_at IS NULL AND $currentPredicate
            """.trimIndent(),
        )
            .param("id", id)
            .param("expectedVersion", expectedVersion)
            .update()
    }

    fun ownerUserId(type: AdminResourceType, id: Long): Long = when (type) {
        AdminResourceType.USER -> id
        AdminResourceType.STUDIO -> jdbcClient.sql("SELECT user_id FROM studios WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getLong("user_id") }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
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

    /** 제품의 User.selectPhotographerType와 같은 불변식을 관리자 생성 경로에도 적용한다. */
    private fun confirmUserType(userId: Long, requiredType: UserType): AdminUserTypeChange? {
        val user = jdbcClient.sql(
            "SELECT user_type, version FROM users WHERE id = :userId AND deleted_at IS NULL FOR UPDATE",
        )
            .param("userId", userId)
            .query { rs, _ -> UserTypeRow(rs.getString("user_type"), rs.getLong("version")) }
            .optional()
            .orElseThrow { AdminException(AdminErrorCode.RESOURCE_NOT_FOUND) }
        if (user.type != null && user.type != requiredType.name) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        if (user.type != null) return null

        val before = find(AdminResourceType.USER, userId)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        val updated = jdbcClient.sql(
            """
            UPDATE users
            SET user_type = :requiredType, version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = :userId AND version = :expectedVersion
              AND user_type IS NULL AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("requiredType", requiredType.name)
            .param("userId", userId)
            .param("expectedVersion", user.version)
            .update()
        if (updated != 1) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        val after = find(AdminResourceType.USER, userId)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        return AdminUserTypeChange(before, after)
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
        val labelExpression: String,
        val searchExpression: String,
        val fields: List<FieldDefinition>,
        val softDeleteColumn: String? = null,
        val defaults: Map<String, Any?> = emptyMap(),
        val generatedSecretField: String? = null,
    ) {
        val summarySql: String = """
            SELECT '${type.name}' AS resource_type,
                   id,
                   version,
                   COALESCE(($labelExpression)::TEXT, '${type.name.lowercase()} #' || id) AS label,
                   ${softDeleteColumn?.let { "$it IS NOT NULL" } ?: "FALSE"} AS deleted,
                   created_at,
                   updated_at,
                   CONCAT_WS(' ', id::TEXT, $searchExpression) AS search_text
            FROM $table
        """.trimIndent()

        val detailSql: String = """
            SELECT id,
                   version,
                   COALESCE(($labelExpression)::TEXT, '${type.name.lowercase()} #' || id) AS label,
                   ${softDeleteColumn?.let { "$it IS NOT NULL" } ?: "FALSE"} AS deleted,
                   created_at,
                   updated_at,
                   ${fields.joinToString { it.column }}
            FROM $table
            WHERE id = :id
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
        val allowedValues: Set<String> = emptySet(),
        val masked: Boolean = false,
    ) {
        fun read(rs: ResultSet, objectMapper: ObjectMapper): Any? {
            val raw = rs.getObject(column)
            if (masked && raw != null) return MASKED
            return when (kind) {
                FieldKind.REVOKED -> raw != null
                FieldKind.DATE_TIME -> raw?.let { rs.getObject(column, OffsetDateTime::class.java) }
                FieldKind.JSON -> raw?.let {
                    objectMapper.readValue(rs.getString(column), Map::class.java).entries
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
                    FieldKind.LONG -> number(value).toLong().also { if (minNumber != null && it < minNumber) invalid() }
                    FieldKind.INT -> number(value).toInt().also { if (minNumber != null && it < minNumber) invalid() }
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

    private data class UserTypeRow(val type: String?, val version: Long)

    data class ResourceCreateResult(
        val id: Long,
        val userTypeChange: AdminUserTypeChange?,
    )

    companion object {
        private const val MASKED = "[MASKED]"
        private const val REDACTED = "[REDACTED]"
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val LEGACY_DIRECT_TRASH_TYPES = setOf(AdminResourceType.GALLERY, AdminResourceType.PHOTO)
        private val RELATION_FIELDS = mapOf(
            AdminResourceType.USER to emptySet(),
            AdminResourceType.STUDIO to setOf("userId"),
            AdminResourceType.GALLERY to setOf("studioId"),
            AdminResourceType.PHOTO to setOf("galleryId"),
            AdminResourceType.SELECTION to setOf("galleryId"),
            AdminResourceType.COLLABORATION to setOf("galleryId"),
            AdminResourceType.ALBUM to setOf("galleryId"),
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
                    FieldDefinition("role", "role", FieldKind.ENUM, allowedValues = setOf("USER", "ADMIN")),
                    // 제품 도메인과 같이 종류 선택은 일회성이다. 관계 workflow가 null만 확정하며,
                    // 일반 CRUD나 리비전 복원이 이미 정해진 종류를 되돌리거나 바꾸지 못한다.
                    FieldDefinition(
                        "userType",
                        "user_type",
                        FieldKind.ENUM,
                        updateAllowed = false,
                        nullable = true,
                        allowedValues = setOf("PHOTOGRAPHER", "CLIENT"),
                    ),
                    FieldDefinition("suspendedAt", "suspended_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("role" to "USER", "userType" to null),
            ),
            ResourceDefinition(
                type = AdminResourceType.STUDIO,
                table = "studios",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, gallery_url, inflow_channel, user_id)",
                fields = listOf(
                    FieldDefinition("userId", "user_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("name", "name", FieldKind.STRING, requiredOnCreate = true, maxLength = 255),
                    FieldDefinition("galleryUrl", "gallery_url", FieldKind.GALLERY_URL, requiredOnCreate = true, maxLength = 255),
                    FieldDefinition("inflowChannel", "inflow_channel", FieldKind.STRING, nullable = true, maxLength = 255),
                    FieldDefinition("suspendedAt", "suspended_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf("inflowChannel" to null),
            ),
            ResourceDefinition(
                type = AdminResourceType.GALLERY,
                table = "galleries",
                labelExpression = "title",
                searchExpression = "CONCAT_WS(' ', title, status, workflow_status, studio_id)",
                fields = listOf(
                    FieldDefinition("studioId", "studio_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
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
                    "selectionDeadline" to null,
                    "maxSelectablePhotoCount" to null,
                    "maxRetouchRoundCount" to null,
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
            ),
            ResourceDefinition(
                type = AdminResourceType.COLLABORATION,
                table = "collab_sessions",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, gallery_id)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
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
                type = AdminResourceType.ALBUM,
                table = "photo_folder_groups",
                labelExpression = "name",
                searchExpression = "CONCAT_WS(' ', name, gallery_id)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("name", "name", FieldKind.STRING, requiredOnCreate = true, maxLength = 100),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
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
