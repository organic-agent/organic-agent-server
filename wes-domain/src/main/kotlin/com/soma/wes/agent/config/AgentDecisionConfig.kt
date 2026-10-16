package com.soma.wes.agent.config

import com.soma.wes.agent.infrastructure.BedrockDecisionClient
import com.soma.wes.agent.infrastructure.DisabledDecisionClient
import com.soma.wes.agent.service.port.DecisionClient
import com.soma.wes.global.config.LlmProperties
import org.springframework.ai.bedrock.converse.BedrockChatOptions
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * 판단기 빈은 하나다 — `app.llm.enabled`가 켜져 있으면 Bedrock, 아니면 503을 내는 구현. 보정 요청 다듬기와 같은 스위치다.
 *
 * Bedrock 클라이언트는 `recommendation`의 것(`InvokeModel`, 재시도 끔)을 같이 쓰지 않고 Spring AI가 만든다 —
 * Converse API를 쓰고, 응답 시간 목표(p95 3초)에 맞춘 호출 제한을 따로 건다.
 */
@Configuration
class AgentDecisionConfig {

    companion object {

        /** 판단 한 번의 상한. 넘으면 503 — 부부는 다시 묻는다. 평소는 p50 1.5초. */
        private val DECISION_TIMEOUT: Duration = Duration.ofSeconds(15)
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "true")
    fun bedrockDecisionClient(
        credentialsProvider: AwsCredentialsProvider,
        properties: LlmProperties,
        objectMapper: ObjectMapper,
    ): DecisionClient {
        val chatModel = BedrockProxyChatModel.builder()
            .credentialsProvider(credentialsProvider)
            .region(Region.of(properties.region))
            .timeout(DECISION_TIMEOUT)
            .options(BedrockChatOptions.builder().model(properties.modelId).build())
            .build()
        return BedrockDecisionClient(chatModel = chatModel, objectMapper = objectMapper, modelId = properties.modelId)
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun disabledDecisionClient(): DecisionClient = DisabledDecisionClient()
}
