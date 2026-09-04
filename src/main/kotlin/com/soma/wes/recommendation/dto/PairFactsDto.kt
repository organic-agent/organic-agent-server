package com.soma.wes.recommendation.dto

/** LLM에 주는 재료 문장들과, 같은 내용의 저장용 구조. 숫자는 전부 여기서 나온다 — LLM은 이것만 안다. */
data class PairFactsDto(
    val sentences: List<String>,
    val facts: Map<String, Any?>,
)
