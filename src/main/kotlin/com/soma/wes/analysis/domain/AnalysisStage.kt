package com.soma.wes.analysis.domain

/**
 * 분석 잡의 단계 = AI repo의 Lambda 하나. 순서는 고정이고 [AnalysisMode]가 어느 단계를 도는지 정한다.
 *
 * - [EMBED]: 미리보기 + DINOv3 벡터 + EXIF (`photos`·`photo_analysis.embedding`). 완료는 이 서버가 `photo_analysis`를
 *   관측해 판정한다 — 임베더는 잡 행을 몰라도 된다.
 * - [SCORE]: CLIP 벡터 + 미학·기술 점수 + 피사체 (`subjects`·`sub_scores`·`clip_embedding`·`model_version`).
 * - [CATEGORIZE]: 백분위·연사·임베딩 그룹 + Bedrock 이름·배정 (`*_pct`·`cluster_*`·`embed_group_id`, `ai_concept_assignments`).
 */
enum class AnalysisStage {
    EMBED,
    SCORE,
    CATEGORIZE,
}
