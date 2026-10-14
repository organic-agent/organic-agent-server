package com.soma.wes.recommendation.dto

import java.time.Duration

/**
 * 구조화 출력 한 번 호출. [schema]는 JSON 스키마(Map)이고 모델은 그 모양으로만 답한다.
 *
 * [timeout]·[maxRetries]는 호출마다 다르다 — 사용자가 기다리는 자연어 해석은 짧은 예산에 재시도 없이,
 * 배치 이유 문장은 넉넉한 예산으로 몇 번 더 시도한다.
 */
data class LlmJsonRequestDto(
    val system: String,
    val parts: List<LlmPartDto>,
    val schema: Map<String, Any>,
    val maxTokens: Int,
    val timeout: Duration? = null,
    val maxRetries: Int = 0,
)
