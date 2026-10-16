package com.soma.wes.agent.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class AgentErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    EMPTY_QUESTION(HttpStatus.BAD_REQUEST, "AGENT_400_1", "질문을 입력해 주세요."),
    EMPTY_ACTION(HttpStatus.BAD_REQUEST, "AGENT_400_2", "실행할 동작이 없습니다."),
    /** 이동이 아닌 동작에 대상 폴더를 주었거나, 동작 하나에 사진이 없다. 계획을 고쳐 보낸 요청이다. */
    INVALID_ACTION(HttpStatus.BAD_REQUEST, "AGENT_400_3", "실행할 수 없는 동작입니다."),
    /** `app.llm.enabled=false`(로컬·테스트 기본값)거나 Bedrock이 답하지 않았다. 잠시 뒤 다시 묻게 한다. */
    DECISION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_503_1", "도우미가 지금 답할 수 없습니다. 잠시 뒤 다시 물어봐 주세요."),
}
