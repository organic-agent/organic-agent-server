package com.soma.wes.support

import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.service.port.DecisionClient

/**
 * 기록형 판단기. 테스트가 정한 주문서를 돌려주고, 받은 질문·문맥을 남긴다.
 * DB 밖 상태라 [DatabaseCleaner]가 모르므로 테스트가 `@BeforeEach`에서 [reset]한다.
 */
class FakeDecisionClient : DecisionClient {

    var decision: AgentDecisionDto? = null
    var failure: RuntimeException? = null
    val questions = mutableListOf<String>()
    val contexts = mutableListOf<AgentContextDto>()

    fun reset() {
        decision = null
        failure = null
        questions.clear()
        contexts.clear()
    }

    override fun decide(question: String, context: AgentContextDto): AgentDecisionDto {
        questions += question
        contexts += context
        failure?.let { throw it }
        return decision ?: error("FakeDecisionClient에 주문서가 설정되지 않았다")
    }
}
