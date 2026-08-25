package com.soma.wes.admin.audit.support

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class AdminAuditSnapshotCodec(
    private val objectMapper: ObjectMapper,
) {

    fun encode(snapshot: Map<String, Any?>?): String? =
        snapshot?.let { objectMapper.writeValueAsString(sanitizeMap(it)) }

    fun decode(snapshot: String?): JsonNode? = snapshot?.let(objectMapper::readTree)

    fun changedFields(
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
    ): List<String> =
        (before.orEmpty().keys + after.orEmpty().keys)
            .distinct()
            .filter { before?.get(it) != after?.get(it) }
            .sorted()

    private fun sanitizeMap(value: Map<String, Any?>): Map<String, Any?> =
        value.mapValues { (key, nested) ->
            if (isSensitiveKey(key)) REDACTED else sanitizeValue(nested)
        }

    private fun sanitizeValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> value.entries.associate { (key, nested) ->
                val stringKey = key.toString()
                stringKey to if (isSensitiveKey(stringKey)) REDACTED else sanitizeValue(nested)
            }
            is Iterable<*> -> value.map(::sanitizeValue)
            is Array<*> -> value.map(::sanitizeValue)
            else -> value
        }

    private fun isSensitiveKey(key: String): Boolean =
        SENSITIVE_KEY_PARTS.any { key.lowercase().contains(it) }

    companion object {
        private const val REDACTED = "[REDACTED]"
        private val SENSITIVE_KEY_PARTS = setOf(
            "password",
            "secret",
            "token",
            "credential",
            "authorization",
            "cookie",
            "oauth",
        )
    }
}
