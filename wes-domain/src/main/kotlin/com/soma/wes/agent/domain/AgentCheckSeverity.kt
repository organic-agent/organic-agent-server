package com.soma.wes.agent.domain

/** 점검 항목의 무게. WARN은 제출을 막거나 빠뜨린 것, INFO는 참고. */
enum class AgentCheckSeverity {
    WARN,
    INFO,
}
