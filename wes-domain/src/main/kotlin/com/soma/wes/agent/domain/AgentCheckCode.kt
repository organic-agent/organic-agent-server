package com.soma.wes.agent.domain

/**
 * 제출 전 점검 항목(#403 5-4). 서버가 세고 서버 문장으로 답한다 — 판단기는 점검을 고르기만 한다(C-1, QA-10).
 * 순서가 답의 번호 순서다. [severity]는 웹이 아이콘을 고르는 데 쓴다.
 */
enum class AgentCheckCode(val severity: AgentCheckSeverity) {
    /** 고른 장수가 계약 장수보다 많다 — 스튜디오 갤러리는 딱 맞춰야 제출된다. */
    OVER_LIMIT(AgentCheckSeverity.WARN),

    /** 사진이 있는 컨셉인데 하나도 안 골랐다. */
    EMPTY_CONCEPT(AgentCheckSeverity.WARN),

    /** 같은 연사 묶음에서 두 장 이상 골랐다. */
    BURST_DUPLICATE(AgentCheckSeverity.INFO),

    /** 고른 사진의 신부 단독·신랑 단독이 한쪽으로 치우쳤다. */
    SUBJECT_IMBALANCE(AgentCheckSeverity.INFO),

    /** 별점 1점 사진이 선택에 담겨 있다. */
    LOW_RATED_SELECTED(AgentCheckSeverity.INFO),
    ;

    /** 도구 인자 코드. `over_limit`. */
    val code: String
        get() = name.lowercase()

    companion object {

        fun fromCode(value: String?): AgentCheckCode? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
