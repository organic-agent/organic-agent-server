package com.soma.wes.recommendation.config

import com.soma.wes.recommendation.infrastructure.BedrockMessagesCodec
import com.soma.wes.recommendation.infrastructure.BedrockStructuredLlmClient
import com.soma.wes.recommendation.infrastructure.DisabledStructuredLlmClient
import com.soma.wes.recommendation.service.port.StructuredLlmClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient

/** [StructuredLlmClient] 빈은 프로퍼티당 하나다 — 켜져 있으면 Bedrock, 아니면 부르는 즉시 실패하는 구현. */
@Configuration
class LlmClientConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "true")
    fun bedrockStructuredLlmClient(
        client: BedrockRuntimeClient,
        properties: LlmProperties,
        codec: BedrockMessagesCodec,
    ): StructuredLlmClient = BedrockStructuredLlmClient(client, properties, codec)

    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun disabledStructuredLlmClient(): StructuredLlmClient = DisabledStructuredLlmClient()
}
