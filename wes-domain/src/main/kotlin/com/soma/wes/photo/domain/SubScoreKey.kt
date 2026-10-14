package com.soma.wes.photo.domain

/**
 * `photo_analysis.sub_scores`(jsonb)에서 이 서버가 읽는 키. 키 이름은 AI repo와의 계약이다 —
 * score 단계(`score/service/classical.py`·`pipeline.py`)와 categorize 단계(`sharpness_pct`)가 쓴다.
 *
 * 키가 없거나 이름이 어긋나면 [PhotoAnalysis.subScore]는 조용히 null을 돌려주고 호출자의 기본값으로 흘러간다.
 * 그래서 리터럴을 흩어 두지 않고 여기 한 곳에 모은다. 이 서버가 읽지 않는 키는 적지 않는다.
 */
enum class SubScoreKey(val key: String) {

    /** 라플라시안 분산 원값. 연사 안에서 가장 선명한 컷을 가를 때 쓴다. */
    SHARPNESS("sharpness"),

    /** 갤러리 안 선명도 백분위(0~100). categorize 단계가 더한다. */
    SHARPNESS_PCT("sharpness_pct"),

    /** 하이라이트가 날아간 픽셀 비율(0~1). */
    HIGHLIGHT_CLIP("highlight_clip"),

    /** 암부가 뭉개진 픽셀 비율(0~1). */
    SHADOW_CLIP("shadow_clip"),

    /** 기술 품질 원점수. 백분위가 아니라 절대 하한 게이트에 쓴다. */
    TECHNICAL_SCORE("technical_score"),

    /** 미학 원점수. 백분위가 아니라 절대 하한 게이트에 쓴다. */
    AESTHETIC_SCORE("aesthetic_score"),
}
