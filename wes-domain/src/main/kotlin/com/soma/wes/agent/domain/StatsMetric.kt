package com.soma.wes.agent.domain

/**
 * 무엇으로 세나. [DUPLICATES]는 같은 연사 묶음에서 두 장 이상 고른 묶음을 센다.
 * [BY_RATING]은 별점 5~1과 별점 없음 장수다. [FOLDER_COUNT]는 사진이 아니라 폴더를 센다 — 컨셉마다 세부 폴더 수(#412 결정 1).
 */
enum class StatsMetric {
    COUNT,
    BY_CONCEPT,
    BY_DETAIL,
    BY_SUBJECTS,
    BY_ORIENTATION,
    BY_RATING,
    DUPLICATES,
    FOLDER_COUNT,
}
