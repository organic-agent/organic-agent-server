package com.soma.wes.agent.config

import com.soma.wes.agent.infrastructure.ConverseDecisionClient
import com.soma.wes.agent.infrastructure.DisabledDecisionClient
import com.soma.wes.agent.infrastructure.OpenAiDecisionClient
import com.soma.wes.agent.service.port.DecisionClient
import com.soma.wes.global.config.LlmProperties
import com.soma.wes.global.config.OpenAiLlmConfig
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * 판단기 빈은 하나다 — `app.llm.enabled`가 꺼져 있으면 503을 내는 구현, 켜져 있으면 `app.llm.provider`에 따라
 * Bedrock Converse(운영) 또는 OpenAI 호환 게이트웨이(로컬·측정). 보정 요청 다듬기와 같은 스위치다.
 *
 * Bedrock 클라이언트는 `recommendation`의 빈(재시도 끔, 다듬기 20초 예산)을 같이 쓰지 않고 여기서 따로 만든다 — 판단은 사용자가
 * 기다리는 호출이라 SDK 기본 재시도(스로틀·5xx)를 켜 두고, 재시도까지 포함한 상한을 [DECISION_TIMEOUT]으로 건다.
 * 빈으로 등록하지 않는다 — 같은 타입 빈이 둘이면 `recommendation`의 주입이 갈린다.
 */
@Configuration
class AgentDecisionConfig {

    companion object {

        /** 판단 한 번의 상한(재시도 포함). 넘으면 503 — 부부는 다시 묻는다. 평소는 p50 1.2초 안팎. */
        private val DECISION_TIMEOUT: Duration = Duration.ofSeconds(15)
    }

    /**
     * 도구 강제·캐시를 켠다. 도구 강제를 거절하는 모델(Sonnet 5.5·Opus 5.5)로 `app.llm.model-id`를 바꾸면 판단이 전부 503이 된다 —
     * 모델을 바꿀 때는 질문 세트(`scripts/agent-eval/run.sh`)로 먼저 잰다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "true")
    @ConditionalOnProperty(prefix = "app.llm", name = ["provider"], havingValue = "bedrock", matchIfMissing = true)
    fun converseDecisionClient(
        credentialsProvider: AwsCredentialsProvider,
        properties: LlmProperties,
        objectMapper: ObjectMapper,
    ): DecisionClient {
        val client = BedrockRuntimeClient.builder()
            .credentialsProvider(credentialsProvider)
            .region(Region.of(properties.region))
            .httpClientBuilder(ApacheHttpClient.builder())
            .overrideConfiguration { it.apiCallTimeout(DECISION_TIMEOUT) }
            .build()
        return ConverseDecisionClient(
            client = client,
            objectMapper = objectMapper,
            modelId = properties.modelId,
            forceTool = true,
            cache = true,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "true")
    @ConditionalOnProperty(prefix = "app.llm", name = ["provider"], havingValue = "openai")
    fun openAiDecisionClient(
        @Qualifier(OpenAiLlmConfig.REST_CLIENT) restClient: RestClient,
        properties: LlmProperties,
        objectMapper: ObjectMapper,
    ): DecisionClient = OpenAiDecisionClient(
        restClient = restClient,
        objectMapper = objectMapper,
        model = properties.openai.model,
        reasoningEffort = properties.openai.reasoningEffort,
    )

    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun disabledDecisionClient(): DecisionClient = DisabledDecisionClient()
}
