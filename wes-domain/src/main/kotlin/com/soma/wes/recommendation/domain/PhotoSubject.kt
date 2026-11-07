package com.soma.wes.recommendation.domain

/**
 * 사진 한 장의 피사체 판정(`photo_analysis.subjects`). 컬럼 주인은 AI score 실행기라 엔티티·projection은 문자열 그대로 두고,
 * 추천 계산의 입구([com.soma.wes.recommendation.dto.RecommendablePhotoDto.from])에서만 이 enum으로 바꾼다 —
 * AI가 새 값을 쓰기 시작해도 읽기가 깨지지 않고 [UNKNOWN]으로 떨어진다.
 */
enum class PhotoSubject(val aiValue: String) {

    BRIDE("bride"),
    GROOM("groom"),
    COUPLE("couple"),

    /** 단체 사진. 폴더 추천에서 뺀다 — 고를 대상이 아니라는 판단(2026-10-04). */
    GROUP("group"),

    /** 판정이 없거나(NULL) 모르는 값. */
    UNKNOWN("unknown"),
    ;

    companion object {

        fun from(aiValue: String?): PhotoSubject = entries.find { it.aiValue == aiValue } ?: UNKNOWN
    }
}
