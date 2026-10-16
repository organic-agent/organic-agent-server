package com.soma.wes.recommendation.config

import com.soma.wes.global.config.LlmProperties
import com.soma.wes.global.config.OpenAiLlmConfig
import com.soma.wes.recommendation.infrastructure.BedrockMessagesCodec
import com.soma.wes.recommendation.infrastructure.BedrockStructuredLlmClient
import com.soma.wes.recommendation.infrastructure.DisabledStructuredLlmClient
import com.soma.wes.recommendation.infrastructure.OpenAiStructuredLlmClient
import com.soma.wes.recommendation.service.port.StructuredLlmClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import tools.jackson.databind.ObjectMapper

/**
 * [StructuredLlmClient] 빈은 프로퍼티당 하나다 — 꺼져 있으면 부르는 즉시 실패하는 구현, 켜져 있으면
 * `app.llm.provider`에 따라 Bedrock(운영) 또는 OpenAI 호환 게이트웨이(로컬·측정).
 */
@Configuration
class LlmClientConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "true")
    @ConditionalOnProperty(prefix = "app.llm", name = ["provider"], havingValue = "bedrock", matchIfMissing = true)
    fun bedrockStructuredLlmClient(
        client: BedrockRuntimeClient,
        properties: LlmProperties,
        codec: BedrockMessagesCodec,
    ): StructuredLlmClient = BedrockStructuredLlmClient(client, properties, codec)

    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "true")
    @ConditionalOnProperty(prefix = "app.llm", name = ["provider"], havingValue = "openai")
    fun openAiStructuredLlmClient(
        @Qualifier(OpenAiLlmConfig.REST_CLIENT) restClient: RestClient,
        properties: LlmProperties,
        objectMapper: ObjectMapper,
    ): StructuredLlmClient = OpenAiStructuredLlmClient(
        restClient = restClient,
        objectMapper = objectMapper,
        model = properties.openai.model,
    )

    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun disabledStructuredLlmClient(): StructuredLlmClient = DisabledStructuredLlmClient()
}
