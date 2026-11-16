package com.soma.wes.agent.domain

/** 이전 대화 한 턴의 화자. Converse `messages`의 user·assistant와 같다. */
enum class AgentTurnRole {
    USER,
    ASSISTANT,
}
