package com.soma.wes.analysis.domain

/**
 * 분석 잡의 생애 — 한 층뿐이다. 단계(stage)는 없다: 사진 한 장의 진행은 `photo_analysis` 행이 말하고,
 * 잡은 갤러리 단위의 "점수가 다 찼나 → categorize 한 번 → 폴더 물질화"만 센다.
 *
 * - [ANALYZING]: 임베딩·점수가 기대 장수만큼 차기를 관측한다. 임베더 배정은 잡과 무관하게 스윕이 한다.
 * - [CATEGORIZING]: categorize EVENT를 보냈고 배정 행·백분위가 차기를 기다린다. 물질화는 이 상태를 닫는 걸음에서 한다.
 * - [DONE] · [FAILED]: 종료. FAILED는 `error`에 이유가 있다.
 *
 * `recommendation`의 `AiJobStatus`와 따로 둔다 — 두 도메인이 enum 하나를 공유하면 한쪽의 전이 규칙 변경이 다른 쪽을 흔든다.
 */
enum class AnalysisStatus {
    ANALYZING,
    CATEGORIZING,
    DONE,
    FAILED,
    ;

    val isActive: Boolean
        get() = this == ANALYZING || this == CATEGORIZING

    companion object {
        /** 갤러리당 하나만 허용되는 상태들. `uk_analysis_jobs_active` 부분 유니크 인덱스와 같은 집합이다. */
        val ACTIVE: Set<AnalysisStatus> = setOf(ANALYZING, CATEGORIZING)
    }
}
