package com.soma.wes.admin.audit.support

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditReasonCategory
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import org.springframework.stereotype.Component

/** 영구 감사 행에는 운영 추적에 필요한 식별자만 남기고 사용자 입력 PII·secret은 제거한다. */
@Component
class AdminAuditSanitizer {

    fun sanitizeText(value: String?): String? {
        var sanitized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        sanitized = EMAIL.replace(sanitized, "[REDACTED_EMAIL]")
        sanitized = PHONE.replace(sanitized, "[REDACTED_PHONE]")
        sanitized = JWT.replace(sanitized, "[REDACTED_TOKEN]")
        sanitized = BEARER.replace(sanitized, "Bearer [REDACTED]")
        sanitized = SECRET_ASSIGNMENT.replace(sanitized) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        return sanitized
    }

    /** 단기 복원 payload는 PII 원문을 복원해야 하므로 credential 패턴만 제거한다. */
    fun sanitizeSecretsOnly(value: String): String =
        SECRET_ASSIGNMENT.replace(
            BEARER.replace(JWT.replace(value, "[REDACTED_TOKEN]"), "Bearer [REDACTED]"),
        ) { match -> "${match.groupValues[1]}=[REDACTED]" }

    /**
     * 운영자가 입력한 자유 텍스트는 영구 감사에 복사하지 않는다. 기존 DTO는 호환을 위해
     * 유지하되, 영구 행에는 서버가 정한 작업 category와 입력 여부만 남긴다.
     */
    @Suppress("UNUSED_PARAMETER")
    fun operatorReason(action: AdminAuditAction, value: String?): String =
        canonicalOperatorReason(value)

    /** audit 이외의 장기 운영 테이블도 같은 허용 category 계약을 사용한다. */
    fun canonicalOperatorReason(value: String?): String =
        "reasonCategory=${AdminAuditReasonCategory.fromOperatorText(value).name} " +
            "operatorReasonProvided=${!value.isNullOrBlank()}"

    /** 신규 정책 이전 행은 자유 문장을 버리고, 명시적으로 허용한 서버 metadata만 보존한다. */
    fun sanitizeStoredReason(value: String?): String {
        val normalized = value?.trim()?.takeIf(String::isNotBlank)
            ?: return canonicalOperatorReason(null)
        if (isAllowedMetadataReason(normalized)) {
            return normalized
        }
        return canonicalOperatorReason(normalized)
    }

    /** 서버 코드가 조립한 route/status 등만 보존한다. 호출자는 사용자 입력을 넘기면 안 된다. */
    fun trustedMetadataReason(action: AdminAuditAction, value: String?): String {
        val normalized = value?.trim()?.takeIf(String::isNotBlank)
            ?: return "category=${action.name} metadataPresent=false"
        return normalized.takeIf(::isAllowedMetadataReason)
            ?: "category=${action.name} metadataRejected=true"
    }

    /**
     * 값의 모양만 검사하면 `note=Alice` 같은 PII도 영구 metadata로 위장할 수 있다.
     * 따라서 서버가 실제로 생성하는 key와 값 domain을 모두 allowlist로 닫는다.
     */
    private fun isAllowedMetadataReason(value: String): Boolean {
        if (value.length > MAX_METADATA_LENGTH) return false
        val tokens = value.split(WHITESPACE)
        if (tokens.isEmpty()) return false
        val seenKeys = mutableSetOf<String>()
        return tokens.all { token ->
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) return@all false
            val key = token.substring(0, separator)
            val metadataValue = token.substring(separator + 1)
            seenKeys.add(key) && when (key) {
                "reasonCategory" -> AdminAuditReasonCategory.entries.any { it.name == metadataValue }
                "operatorReasonProvided", "metadataPresent", "metadataRejected",
                "queryFilterPresent", "typeFilterPresent",
                -> metadataValue == "true" || metadataValue == "false"
                "category" -> AdminAuditAction.entries.any { it.name == metadataValue }
                "route" -> metadataValue in SAFE_ROUTES
                "end" -> metadataValue == "EXPIRED"
                "method" -> metadataValue in SAFE_HTTP_METHODS
                "status" -> metadataValue.toIntOrNull()?.let { it in 100..599 } == true
                "page", "size", "typeFilterCount", "returnedCount", "totalCount" ->
                    NON_NEGATIVE_NUMBER.matches(metadataValue)
                "adminSessionId" -> UUID_IDENTIFIER.matches(metadataValue)
                else -> false
            }
        }
    }

    fun sanitizeTargetId(targetType: AdminAuditTargetType?, targetId: String?): String? {
        val normalized = targetId?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (targetType == AdminAuditTargetType.AUTHENTICATION) return null
        return normalized.takeIf { SAFE_IDENTIFIER.matches(it) }?.take(128)
    }

    /** 운영자 이름을 장기 복제하지 않고 불변 actor id만 사람이 읽을 수 있게 표시한다. */
    fun canonicalActorLabel(actorAdminId: Long?): String? = actorAdminId?.let { "ADMIN #$it" }

    fun canonicalTargetLabel(
        targetType: AdminAuditTargetType?,
        sanitizedTargetId: String?,
        suppliedLabel: String?,
    ): String? {
        if (targetType == null) return null
        if (targetType == AdminAuditTargetType.AUTHENTICATION) return targetType.name
        val suffix = sanitizedTargetId?.let { " #$it" }.orEmpty()
        return "${targetType.name}$suffix".take(120)
    }

    fun sanitizeCorrelationId(value: String?): String? =
        value?.trim()?.takeIf { CORRELATION_ID.matches(it) }

    companion object {
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val PHONE = Regex("(?<![0-9])(?:01[016789]-?[0-9]{3,4}-?[0-9]{4})(?![0-9])")
        private val JWT = Regex("\\b[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")
        private val BEARER = Regex("(?i)Bearer\\s+[^\\s,;]+")
        private val SECRET_ASSIGNMENT = Regex(
            "(?i)\\b(password|token|secret|credential|authorization|cookie|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+",
        )
        private val SAFE_IDENTIFIER = Regex("(?:[0-9]+|[0-9a-fA-F-]{36}|[A-Z][A-Z0-9_.-]{0,63})")
        private const val MAX_METADATA_LENGTH = 500
        private val WHITESPACE = Regex("\\s+")
        private val NON_NEGATIVE_NUMBER = Regex("(?:0|[1-9][0-9]{0,18})")
        private val UUID_IDENTIFIER = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val SAFE_HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        private val SAFE_ROUTES = setOf(
            "RESOURCE_LIST",
            "RESOURCE_DETAIL",
            "RESOURCE_CONTEXT",
            "RESOURCE_READ",
            "ADMIN_ACCOUNT_LIST",
            "OPERATIONS_OVERVIEW",
            "OBSERVABILITY_LINKS",
            "TRASH_LIST",
            "CHILD_TRASH_LIST",
            "SYSTEM_SETTINGS",
            "AUTH_SESSION",
            "IMPERSONATION_READ",
            "ADMIN_READ",
            "AUDIT_LOG_LIST",
            "REVISION_LIST",
            "AUDIT_LOG_DETAIL",
            "AUDIT_LOG_READ",
            "MUTATION_FAILURE",
            "RESPONSE_FAILURE",
        )
        /** `HttpLoggingFilter`가 발급하는 요청별 trace 형식. UUID나 운영 식별자를 겸용하지 않는다. */
        private val CORRELATION_ID = Regex("[0-9a-f]{16}")
    }
}
