package com.soma.wes.support

import com.soma.wes.recommendation.dto.LlmJsonRequestDto
import com.soma.wes.recommendation.service.port.StructuredLlmClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * 기록형 LLM. 테스트가 정한 JSON을 돌려주거나 정한 예외를 던진다 — AI repo 테스트의 `FakeCompareLlm`과 같은 역할.
 * DB 밖 상태라 [DatabaseCleaner]가 모르므로 테스트가 `@BeforeEach`에서 [reset]한다.
 */
class FakeStructuredLlmClient : StructuredLlmClient {

    private val objectMapper = ObjectMapper()

    override var isEnabled: Boolean = true

    var response: JsonNode? = null
    var failure: RuntimeException? = null
    val requests = mutableListOf<LlmJsonRequestDto>()

    val calls: Int
        get() = requests.size

    fun respondWith(json: String) {
        response = objectMapper.readTree(json)
    }

    fun reset() {
        isEnabled = true
        response = null
        failure = null
        requests.clear()
    }

    override fun completeJson(request: LlmJsonRequestDto): JsonNode {
        requests += request
        failure?.let { throw it }
        return response ?: error("FakeStructuredLlmClient에 응답이 설정되지 않았다")
    }
}
