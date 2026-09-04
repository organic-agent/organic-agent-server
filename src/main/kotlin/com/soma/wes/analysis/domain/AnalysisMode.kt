package com.soma.wes.analysis.domain

/**
 * 분석 잡의 종류 = 어느 단계([AnalysisStage])를 순서대로 도는가. 프론트 계약(`mode`)이고 DB CHECK와 값이 같다.
 *
 * 작가가 보는 결과는 언제나 카테고리까지 끝난 사진이다 — 임베딩만 따로 도는 모드는 없다([FULL]이 EMBED부터 시작한다).
 * [NAMING]만 따로 있는 이유는 실행 시간이다 — 7000장에서 임베딩·점수는 수십 분, 이름 붙이기는 1~2분이라
 * VLM 실패나 작가 정의 컨셉 변경 때는 이름만 다시 돌린다.
 */
enum class AnalysisMode(val stages: List<AnalysisStage>) {
    /** 전체: 미리보기·임베딩 → 사진별 점수 → 그룹·이름. 이미 임베딩된 사진은 첫 단계가 건너뛰므로 다시 눌러도 안전하다. */
    FULL(listOf(AnalysisStage.EMBED, AnalysisStage.SCORE, AnalysisStage.CATEGORIZE)),

    /** 이름·배정만: [FULL]의 산출물 위에서 그룹마다 (큰 분류, 컨셉)을 정해 `ai_concept_assignments`에 남긴다. */
    NAMING(listOf(AnalysisStage.CATEGORIZE)),
    ;

    val firstStage: AnalysisStage
        get() = stages.first()

    /** [stage] 다음 단계. 마지막이면 null — 잡을 닫을 차례다. */
    fun next(stage: AnalysisStage): AnalysisStage? {
        val index = stages.indexOf(stage)
        return if (index < 0) null else stages.getOrNull(index + 1)
    }
}
