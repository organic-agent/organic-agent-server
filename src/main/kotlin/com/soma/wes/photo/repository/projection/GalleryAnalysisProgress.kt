package com.soma.wes.photo.repository.projection

/**
 * 갤러리 한 개의 분석 진행 — 사진 행과 분석 행을 LEFT JOIN 해 한 번에 센 값. 전부 휴지통 제외다.
 *
 * 사진 상태는 "S3에 있나"만 말하므로 나머지는 분석 행의 컬럼 유무로 판정한다. 분석 잡의 진행률 응답과
 * `/photos/summary`가 같은 값을 돌려주도록 프로젝션을 하나만 둔다.
 */
data class GalleryAnalysisProgress(
    /** 전체(PENDING 포함). */
    val total: Long,
    /** S3에 아직 없을 수 있는 사진. */
    val pending: Long,
    /** 최근에 발급돼 아직 올라오는 중일 수 있는 PENDING — 분석 완료 판정은 이 값이 0이 되기를 기다린다. */
    val livePending: Long,
    /** 분석이 결정적으로 실패한 사진(`photo_analysis.error`). 기대 장수에서 빠진다. */
    val failed: Long,
    /** 분석 대상 — UPLOADED 이고 실패하지 않은 사진. */
    val expected: Long,
    /** 대상 중 DINOv3 벡터가 있는 사진. */
    val embedded: Long,
    /** 대상 중 CLIP 벡터(점수)까지 있는 사진. */
    val scored: Long,
    /** 대상 중 백분위까지 있는 사진 — categorize가 끝난 사진. */
    val categorized: Long,
) {

    /** S3에 원본이 있는 사진 전부 — 대상과 실패를 합친 것. */
    val uploaded: Long
        get() = expected + failed

    val isFullyScored: Boolean
        get() = expected > 0 && scored == expected

    /** 올라오는 중인 사진도 분석 대상도 남지 않았다(전부 실패·삭제) — 분석 잡을 닫을 때다. */
    val hasNothingToAnalyze: Boolean
        get() = livePending == 0L && pending == 0L && expected == 0L

    /** 업로드가 잠잠해졌고 대상 전부에 점수가 있다 — categorize를 보낼 때다. */
    val isReadyToCategorize: Boolean
        get() = livePending == 0L && isFullyScored

    /** 대상 전부에 백분위가 있다 — categorize가 모든 사진을 지났다. */
    val isFullyCategorized: Boolean
        get() = categorized == expected
}
