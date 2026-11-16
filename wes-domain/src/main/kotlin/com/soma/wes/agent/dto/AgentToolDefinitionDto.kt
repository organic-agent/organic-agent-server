package com.soma.wes.agent.dto

/** 판단기에게 보여 주는 도구 하나의 정의. 판단기 구현이 자기 API 모양(Converse `toolSpec`)으로 옮겨 싣는다. */
data class AgentToolDefinitionDto(
    val name: String,
    val description: String,
    /** JSON 스키마 문자열. */
    val inputSchema: String,
)
