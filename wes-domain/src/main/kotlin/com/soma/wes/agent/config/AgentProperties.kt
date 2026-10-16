package com.soma.wes.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 셀렉 도우미 손잡이. 질문 한 번이 LLM 호출 한 번이라, 사용자 한 명이 [window] 동안 [maxQuestions]번까지만 묻게 한다 —
 * 스크립트·반복 클릭이 비용을 끌어올리는 것을 막는 상한이지 평소 사용을 막는 값이 아니다(셀렉 한 번에 수십 번 묻는다고 본다).
 */
@ConfigurationProperties(prefix = "app.agent")
data class AgentProperties(
    val maxQuestions: Int = 60,
    val window: Duration = Duration.ofMinutes(10),
)
