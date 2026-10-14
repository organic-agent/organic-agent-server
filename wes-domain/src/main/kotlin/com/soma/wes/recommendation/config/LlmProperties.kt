package com.soma.wes.recommendation.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bedrock(Anthropic Messages) 호출 손잡이.
 *
 * [enabled]가 false면 LLM을 부르지 않는 구현이 꽂힌다 — LLM을 쓰는 기능(보정 요청 다듬기)은 다듬지 못했다고
 * 답한다. 로컬·테스트 기본값이고, prod는 Parameter Store가 켠다.
 */
@ConfigurationProperties(prefix = "app.llm")
data class LlmProperties(
    val enabled: Boolean = false,

    /** Bedrock 리전. 서울에는 Sonnet 온디맨드가 없어 [modelId]는 `global.` 크로스 리전 프로필이다. */
    val region: String = "ap-northeast-2",

    val modelId: String = "global.anthropic.claude-sonnet-4-6",
)
