package com.soma.wes.agent.service.port

import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto

/**
 * 질문 하나를 주문서 하나로 바꾸는 판단기. 판단 한 번에 LLM 호출 한 번이다(응답 p50 ≤ 1.6초 목표).
 *
 * 구현은 갈아 끼우는 자리다 — 운영은 Bedrock Converse, 로컬·측정은 OpenAI 호환 게이트웨이, 나중에 Jev·규칙 판단기를 같은 질문 세트로 비교한다.
 * 판단할 수 없으면(꺼져 있음·Bedrock 실패) `AgentException(DECISION_UNAVAILABLE)`을 던진다.
 */
interface DecisionClient {

    fun decide(question: String, context: AgentContextDto): AgentDecisionDto
}
