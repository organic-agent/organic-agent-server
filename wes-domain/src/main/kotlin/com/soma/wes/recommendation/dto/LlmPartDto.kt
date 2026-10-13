package com.soma.wes.recommendation.dto

/** LLM user 메시지 한 조각. 텍스트 또는 JPEG 바이트 — AI repo `llm.Part`와 같은 두 종류다. */
sealed class LlmPartDto {

    data class Text(val text: String) : LlmPartDto()

    class Image(val jpeg: ByteArray) : LlmPartDto()
}
