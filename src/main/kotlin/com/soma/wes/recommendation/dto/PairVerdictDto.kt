package com.soma.wes.recommendation.dto


/** 비교샷 판정의 내부 전달형. [com.soma.wes.recommendation.service.PairVerdictJudge]가 만들고 응답 DTO가 그대로 감싼다. */
data class PairVerdictDto(
    val chosenPhotoId: Long,

    /** clear(뚜렷한 차이) | slight(거의 같아요). */
    val confidence: String,

    val reason: String,

    /** llm(사진을 본 판정) | template(타임아웃·실패 시 수치만으로). */
    val source: String,

    /** 같은 쌍의 저장된 판정을 그대로 돌려준 경우 true. */
    val cached: Boolean = false,
)
