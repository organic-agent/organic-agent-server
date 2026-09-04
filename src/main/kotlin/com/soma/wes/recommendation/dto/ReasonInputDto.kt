package com.soma.wes.recommendation.dto

/**
 * 이유 문장 한 장의 입력. [image]가 null이면 텍스트 재료만 보낸다("(사진 없음)"). [fallback]은 LLM이 답하지 못했을 때의 템플릿 문장.
 */
data class ReasonInputDto(
    val photoId: Long,
    val primary: String,
    val facts: List<String>,
    val fallback: String,
    val image: ByteArray? = null,
    val siblings: List<SiblingImageDto> = emptyList(),
)
