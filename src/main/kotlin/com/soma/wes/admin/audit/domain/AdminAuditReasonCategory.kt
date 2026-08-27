package com.soma.wes.admin.audit.domain

/**
 * 영구 감사 로그에 저장할 수 있는 변경 사유 분류.
 *
 * 운영자가 입력한 설명 원문은 PII를 포함할 수 있어 저장하지 않고, 요청 문자열의 선두에
 * 명시된 이 코드만 파싱한다. 미지정 또는 알 수 없는 코드는 [UNSPECIFIED]로 닫는다.
 */
enum class AdminAuditReasonCategory {
    CUSTOMER_REQUEST,
    DATA_CORRECTION,
    INCIDENT_RECOVERY,
    SECURITY_RESPONSE,
    POLICY_ENFORCEMENT,
    TEST_OPERATION,
    OTHER,
    UNSPECIFIED,
    ;

    companion object {
        private val accepted = entries.filterNot { it == UNSPECIFIED }.associateBy { it.name }
        private val prefix = Regex(
            "^\\s*(?:\\[([A-Z_]+)]|([A-Z_]+)\\s*:|reasonCategory=([A-Z_]+)(?:\\s|$))",
        )

        fun fromOperatorText(value: String?): AdminAuditReasonCategory {
            val normalized = value?.takeIf(String::isNotBlank) ?: return UNSPECIFIED
            val match = prefix.find(normalized) ?: return UNSPECIFIED
            val code = match.groupValues.drop(1).firstOrNull(String::isNotBlank) ?: return UNSPECIFIED
            return accepted[code] ?: UNSPECIFIED
        }
    }
}
