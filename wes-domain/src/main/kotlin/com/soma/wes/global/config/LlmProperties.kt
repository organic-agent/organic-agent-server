package com.soma.wes.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * LLM 호출 손잡이. 보정 요청 다듬기(`recommendation`)와 셀렉 도우미(`agent`)가 같이 쓴다.
 *
 * [enabled]가 false면 LLM을 부르지 않는 구현이 꽂힌다 — 보정 요청 다듬기는 다듬지 못했다고 답하고,
 * 셀렉 도우미는 쓸 수 없다고 답한다. 로컬·테스트 기본값이고, prod는 Parameter Store가 켠다.
 *
 * [provider]는 켜졌을 때 어디로 부르는가다. 운영은 Bedrock, 로컬 개발·측정은 OpenAI 호환 게이트웨이
 * (`scripts/llm`의 codex-lb + LiteLLM, ChatGPT 구독 계정)로 돌려 LLM 비용을 0으로 만든다.
 */
@ConfigurationProperties(prefix = "app.llm")
data class LlmProperties(
    val enabled: Boolean = false,

    val provider: Provider = Provider.BEDROCK,

    /** Bedrock 리전. 서울에는 Sonnet 온디맨드가 없어 [modelId]는 `global.` 크로스 리전 프로필이다. */
    val region: String = "ap-northeast-2",

    val modelId: String = "global.anthropic.claude-sonnet-4-6",

    val openai: OpenAi = OpenAi(),
) {

    enum class Provider { BEDROCK, OPENAI }

    /** OpenAI 호환 `/chat/completions` 게이트웨이. [apiKey]는 LiteLLM 관리자 키다(`scripts/llm/.env`). */
    data class OpenAi(
        val baseUrl: String = "http://localhost:4100/v1",
        val apiKey: String = "",
        val model: String = "codex-terra",
        /** 추론 모델의 생각 깊이. 판단기는 짧은 분류라 낮게 둔다 — 지연이 곧 사용자 대기다. */
        val reasoningEffort: String = "low",
    )
}
