package com.soma.wes.admin.resource.repository.projection

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType

internal data class AdminResourceDefinition(
    val type: AdminResourceType,
    val table: String,
    val idColumn: String = "id",
    val labelExpression: String,
    val searchExpression: String,
    val fields: List<AdminResourceFieldDefinition>,
    val softDeleteColumn: String? = null,
    val defaults: Map<String, Any?> = emptyMap(),
    val generatedSecretField: String? = null,
    val createSupported: Boolean = true,
    val updateSupported: Boolean = true,
) {
    val responseFields: List<AdminResourceFieldDefinition> = fields.filterNot { it.name == "collabToken" }

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
               ${responseFields.joinToString { it.selectExpression }}
        FROM $table
        WHERE $idColumn = :id
    """.trimIndent()

    fun field(name: String): AdminResourceFieldDefinition =
        fields.find { it.name == name } ?: throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
}
