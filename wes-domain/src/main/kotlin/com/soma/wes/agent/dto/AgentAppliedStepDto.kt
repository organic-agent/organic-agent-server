package com.soma.wes.agent.dto

import com.soma.wes.agent.domain.AgentActionStep

/** 실행한 동작 하나의 결과 — 실제로 바뀐 것과, 그것을 되돌리는 동작들(이동은 이전 폴더마다 하나). */
data class AgentAppliedStepDto(
    val applied: AgentActionStep,
    val undo: List<AgentActionStep>,
)
