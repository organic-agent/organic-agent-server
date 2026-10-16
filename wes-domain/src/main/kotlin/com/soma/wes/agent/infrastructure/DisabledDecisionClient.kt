package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import com.soma.wes.agent.service.port.DecisionClient

/** `app.llm.enabled=false`(로컬·테스트 기본값)일 때 꽂힌다. 판단 없이는 답할 수 없어 503으로 알린다. */
class DisabledDecisionClient : DecisionClient {

    override fun decide(question: String, context: AgentContextDto): AgentDecisionDto =
        throw AgentException(AgentErrorCode.DECISION_UNAVAILABLE)
}
