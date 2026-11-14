package com.soma.wes.agent.config

import com.soma.wes.agent.infrastructure.ConverseDecisionClient
import com.soma.wes.agent.infrastructure.DisabledDecisionClient
import com.soma.wes.agent.service.port.DecisionClient
import com.soma.wes.recommendation.config.LlmProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * 판단기 빈은 하나다 — `app.llm.enabled`와 `app.agent.enabled`가 모두 켜져야 Bedrock Converse, 하나라도 꺼져 있으면 503을 내는 구현.
 * 도우미 전용 스위치를 따로 둔 까닭은 운영의 `app.llm.enabled`가 보정 요청 다듬기 때문에 이미 켜져 있어서다 — 배포와 켜기를 나누고,
 * 도우미만 끌 수 있게 한다(#403 6단계).
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
     * 모델을 바꿀 때는 질문 세트(`scripts/dev-tools/agent-eval.sh`)로 먼저 잰다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "true")
    @ConditionalOnProperty(prefix = "app.agent", name = ["enabled"], havingValue = "true")
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

    /** 위 빈이 없을 때만. 같은 설정 클래스 안에서 위 메서드가 먼저 평가되므로 순서에 기대도 된다. */
    @Bean
    @ConditionalOnMissingBean(DecisionClient::class)
    fun disabledDecisionClient(): DecisionClient = DisabledDecisionClient()
}
