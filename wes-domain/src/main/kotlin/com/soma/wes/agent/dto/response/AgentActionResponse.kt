package com.soma.wes.agent.dto.response

import com.soma.wes.agent.domain.AgentActionStep
import com.soma.wes.agent.dto.request.AgentActionRequest
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "실행 결과와 되돌리기 쪽지")
data class AgentActionResponse(

    @field:Schema(description = "실제로 바뀐 것. 이미 그 상태였던 사진은 빠진다")
    val applied: List<AgentActionStep>,

    @field:Schema(description = "되돌리기 — 이것을 그대로 실행 창구로 보내면 원래대로 돌아간다. 바뀐 것이 없으면 steps가 비어 있다")
    val undo: AgentActionRequest,
)
