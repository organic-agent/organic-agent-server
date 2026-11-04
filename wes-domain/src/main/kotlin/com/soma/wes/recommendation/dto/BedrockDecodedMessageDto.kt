package com.soma.wes.recommendation.dto


data class BedrockDecodedMessageDto(
    val stopReason: String?,
    val text: String?,
    val inputTokens: Int,
    val outputTokens: Int,
)
