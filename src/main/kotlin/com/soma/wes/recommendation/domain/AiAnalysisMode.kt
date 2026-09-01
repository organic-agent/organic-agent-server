package com.soma.wes.recommendation.domain

/**
 * 분석 잡의 종류. 사진별 분석과 이름 붙이기를 나누는 이유는 실행 시간이다 — 7000장에서
 * [FULL]은 30~40분, [NAMING]은 1~2분이라, VLM 실패나 작가 정의 컨셉 변경 때 이름만 다시 돌린다.
 */
enum class AiAnalysisMode {

    /** 사진별 분석 전체: 임베딩 그룹·피사체·점수·클러스터를 `photo_analysis`에 적재하고, 끝에 이름 붙이기까지 이어 돈다. */
    FULL,

    /** 이름·배정만: [FULL]의 산출물 위에서 그룹마다 (큰 분류, 컨셉)을 정해 `ai_concept_assignments`에 남긴다. */
    NAMING,
}
