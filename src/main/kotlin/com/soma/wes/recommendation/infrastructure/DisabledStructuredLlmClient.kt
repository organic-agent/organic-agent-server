package com.soma.wes.recommendation.infrastructure

import com.soma.wes.recommendation.dto.LlmJsonRequestDto
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.service.port.StructuredLlmClient
import tools.jackson.databind.JsonNode

/** `app.llm.enabled=false`의 구현. 호출자는 [isEnabled]를 보고 LLM 없는 경로로 가야 하며, 그래도 부르면 실패다. */
class DisabledStructuredLlmClient : StructuredLlmClient {

    override val isEnabled: Boolean = false

    override fun completeJson(request: LlmJsonRequestDto): JsonNode =
        throw RecommendationException(RecommendationErrorCode.LLM_CALL_FAILED)
}
