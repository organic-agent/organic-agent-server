package com.soma.wes.recommendation.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * AI 비교샷 응답의 내부 전달형. 필드명은 AI repo(photoselect v3 `compare._response`)의 JSON 키와
 * 같다 — 어댑터가 역직렬화로 바로 만든다. 모르는 키(pipeline, mode, elapsedSeconds …)는 버린다 —
 * AI가 응답에 키를 더해도 wes가 깨지지 않아야 해서 매퍼 설정이 아니라 타입에 박는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PairVerdictDto(
    val chosenPhotoId: Long,

    /** clear(뚜렷한 차이) | slight(거의 같아요). 어휘 계약은 AI repo가 진다. */
    val confidence: String,

    val reason: String,

    /** llm(사진을 본 판정) | template(타임아웃·실패 시 수치만으로). */
    val source: String,

    /** 같은 쌍의 저장된 판정을 그대로 돌려준 경우 true. */
    val cached: Boolean = false,
)
