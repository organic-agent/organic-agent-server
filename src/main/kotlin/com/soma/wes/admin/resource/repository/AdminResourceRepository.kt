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

@Repository
class AdminResourceRepository(
    private val jdbcClient: JdbcClient,
    private val secureTokenGenerator: SecureTokenGenerator,
) {

    fun findPhotoOriginal(photoId: Long): PhotoOriginal? = jdbcClient.sql(
        """
            SELECT id, original_file_name, storage_key
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
                SELECT COUNT(*) FROM photos
                WHERE deleted_at IS NULL
                  AND status = 'UPLOADED'
                  AND embedding IS NULL
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
                (SELECT COUNT(*) FROM galleries WHERE deleted_at IS NOT NULL) +
                (SELECT COUNT(*) FROM photos WHERE deleted_at IS NOT NULL)
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

    fun create(type: AdminResourceType, fields: Map<String, Any?>): Long {
        val definition = definition(type)
        val normalized = normalizeFields(definition, fields, creating = true).toMutableMap()
        definition.defaults.forEach { (name, value) -> normalized.putIfAbsent(name, value) }
        definition.generatedSecretField?.let { normalized[it] = secureTokenGenerator.generate() }

        val columns = normalized.keys.map { definition.field(it).column }
        val parameters = normalized.keys.map { ":$it" }
        val sql = """
            INSERT INTO ${definition.table} (${columns.joinToString()}, version, created_at, updated_at)
            VALUES (${parameters.joinToString()}, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
        """.trimIndent()
        var statement = jdbcClient.sql(sql)
        normalized.forEach { (name, value) -> statement = statement.param(name, value) }
        return statement.query { rs, _ -> rs.getLong("id") }.single()
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

    fun delete(type: AdminResourceType, id: Long, expectedVersion: Long): Int {
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
            fields = definition.fields.associate { field -> field.name to field.read(rs) },
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
        fun read(rs: ResultSet): Any? {
            val raw = rs.getObject(column)
            if (masked && raw != null) return MASKED
            return when (kind) {
                FieldKind.REVOKED -> raw != null
                FieldKind.DATE_TIME -> raw?.let { rs.getObject(column, OffsetDateTime::class.java) }
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
                    FieldKind.BOOLEAN -> when (value) {
                        is Boolean -> value
                        is String -> value.toBooleanStrict()
                        else -> invalid()
                    }
                    FieldKind.DATE_TIME -> OffsetDateTime.parse(value.toString())
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

    private enum class FieldKind { STRING, EMAIL, GALLERY_URL, ENUM, LONG, INT, BOOLEAN, DATE_TIME, REVOKED }

    companion object {
        private const val MASKED = "[MASKED]"
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

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
                    FieldDefinition("userType", "user_type", FieldKind.ENUM, nullable = true, allowedValues = setOf("PHOTOGRAPHER", "CLIENT")),
                ),
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
                ),
                defaults = mapOf("inflowChannel" to null),
            ),
            ResourceDefinition(
                type = AdminResourceType.GALLERY,
                table = "galleries",
                labelExpression = "title",
                searchExpression = "CONCAT_WS(' ', title, status, studio_id)",
                fields = listOf(
                    FieldDefinition("studioId", "studio_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("title", "title", FieldKind.STRING, requiredOnCreate = true, maxLength = 100),
                    FieldDefinition("status", "status", FieldKind.ENUM, allowedValues = setOf("DRAFT", "OPEN", "CLOSED")),
                    FieldDefinition("selectionDeadline", "selection_deadline", FieldKind.DATE_TIME, nullable = true),
                    FieldDefinition("maxSelectablePhotoCount", "max_selectable_photo_count", FieldKind.INT, nullable = true, minNumber = 1),
                    FieldDefinition("maxRetouchRoundCount", "max_retouch_round_count", FieldKind.INT, nullable = true, minNumber = 1),
                    FieldDefinition("deletedAt", "deleted_at", FieldKind.DATE_TIME, createAllowed = false, updateAllowed = false, nullable = true),
                ),
                softDeleteColumn = "deleted_at",
                defaults = mapOf(
                    "status" to "DRAFT",
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
                    FieldDefinition("status", "status", FieldKind.ENUM, allowedValues = setOf("SELECTING", "SUBMITTED")),
                    FieldDefinition("submittedAt", "submitted_at", FieldKind.DATE_TIME, nullable = true),
                ),
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
                    FieldDefinition("revoked", "revoked_at", FieldKind.REVOKED, createAllowed = false, nullable = true),
                ),
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
                ),
            ),
            ResourceDefinition(
                type = AdminResourceType.RETOUCH_REQUEST,
                table = "retouch_rounds",
                labelExpression = "'retouch #' || id || ' / round ' || round_no",
                searchExpression = "CONCAT_WS(' ', gallery_id, round_no, status)",
                fields = listOf(
                    FieldDefinition("galleryId", "gallery_id", FieldKind.LONG, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("roundNo", "round_no", FieldKind.INT, requiredOnCreate = true, updateAllowed = false, minNumber = 1),
                    FieldDefinition("status", "status", FieldKind.ENUM, allowedValues = setOf("DRAFTING", "REQUESTED", "COMPLETED")),
                    FieldDefinition("requestedAt", "requested_at", FieldKind.DATE_TIME, nullable = true),
                    FieldDefinition("completedAt", "completed_at", FieldKind.DATE_TIME, nullable = true),
                ),
                defaults = mapOf("status" to "DRAFTING", "requestedAt" to null, "completedAt" to null),
            ),
        ).associateBy(ResourceDefinition::type)
    }

    data class PhotoOriginal(
        val id: Long,
        val originalFileName: String,
        val storageKey: String,
    )
}
