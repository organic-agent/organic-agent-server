package com.soma.wes.agent.domain

/** 무엇으로 세나. [DUPLICATES]는 같은 연사 묶음에서 두 장 이상 고른 묶음을 센다. */
enum class StatsMetric {
    COUNT,
    BY_CONCEPT,
    BY_DETAIL,
    BY_SUBJECTS,
    BY_ORIENTATION,
    DUPLICATES,
}
