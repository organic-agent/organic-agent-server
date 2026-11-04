package com.soma.wes.admin.resource.repository.projection

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.studio.domain.Studio
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.Locale
import tools.jackson.databind.ObjectMapper

internal data class AdminResourceFieldDefinition(
    val name: String,
    val column: String,
    val kind: AdminResourceFieldKind,
    val requiredOnCreate: Boolean = false,
    val createAllowed: Boolean = true,
    val updateAllowed: Boolean = true,
    val nullable: Boolean = false,
    val maxLength: Int? = null,
    val minNumber: Long? = null,
    val maxNumber: Long? = null,
    val allowedValues: Set<String> = emptySet(),
    val readExpression: String? = null,
    val readColumn: String = column,
) {
    val selectExpression: String
        get() = readExpression ?: column

    companion object {
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }

    fun read(rs: ResultSet, objectMapper: ObjectMapper): Any? {
        val raw = rs.getObject(readColumn)
        return when (kind) {
            AdminResourceFieldKind.REVOKED -> raw != null
            AdminResourceFieldKind.DATE_TIME -> raw?.let { rs.getObject(readColumn, OffsetDateTime::class.java) }
            AdminResourceFieldKind.JSON -> raw?.let {
                objectMapper.readValue(rs.getString(readColumn), Map::class.java).entries
                    .associate { (key, value) -> key.toString() to value }
            }
            AdminResourceFieldKind.STRING,
            AdminResourceFieldKind.EMAIL,
            AdminResourceFieldKind.GALLERY_URL,
            AdminResourceFieldKind.ENUM,
            AdminResourceFieldKind.LONG,
            AdminResourceFieldKind.INT,
            AdminResourceFieldKind.DECIMAL,
            AdminResourceFieldKind.BOOLEAN,
            -> raw
        }
    }

    fun normalize(value: Any?, now: OffsetDateTime): Any? {
        if (value == null) {
            if (!nullable) throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            return null
        }
        return try {
            when (kind) {
                AdminResourceFieldKind.STRING -> value.toString().trim().also {
                    if (it.isEmpty() || (maxLength != null && it.length > maxLength)) invalid()
                }
                AdminResourceFieldKind.EMAIL -> value.toString().trim().also {
                    if (it.length > (maxLength ?: Int.MAX_VALUE) || !EMAIL.matches(it)) invalid()
                }
                AdminResourceFieldKind.GALLERY_URL -> Studio.validateGalleryUrl(value.toString())
                AdminResourceFieldKind.ENUM -> value.toString().uppercase(Locale.ROOT).also {
                    if (it !in allowedValues) invalid()
                }
                AdminResourceFieldKind.LONG -> number(value).toLong().also {
                    if ((minNumber != null && it < minNumber) || (maxNumber != null && it > maxNumber)) invalid()
                }
                AdminResourceFieldKind.INT -> number(value).toInt().also {
                    if ((minNumber != null && it < minNumber) || (maxNumber != null && it > maxNumber)) invalid()
                }
                AdminResourceFieldKind.DECIMAL -> invalid()
                AdminResourceFieldKind.BOOLEAN -> when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrict()
                    else -> invalid()
                }
                AdminResourceFieldKind.DATE_TIME -> OffsetDateTime.parse(value.toString())
                AdminResourceFieldKind.JSON -> invalid()
                AdminResourceFieldKind.REVOKED -> when (value) {
                    is Boolean -> if (value) now else null
                    is String -> if (value.toBooleanStrict()) now else null
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
