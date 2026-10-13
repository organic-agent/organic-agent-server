package com.soma.wes.recommendation.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient

/**
 * Bedrock Runtime 클라이언트. `app.llm.enabled=true`일 때만 만든다 — 로컬·테스트는 자격증명 없이 기동해야 한다.
 *
 * 자격증명은 다른 AWS 클라이언트와 같은 소스(Spring Cloud AWS의 프로바이더 빈)를 쓰고, 리전은 기본 리전이
 * 아니라 [LlmProperties.region]이다 — 모델 액세스가 리전 단위라 S3·SSM과 갈릴 수 있다.
 *
 * SDK 재시도는 끈다. 재시도 여부는 요청마다 다를 수 있어([com.soma.wes.recommendation.dto.LlmJsonRequestDto.maxRetries])
 * 어댑터가 직접 돈다.
 *
 * HTTP 소켓 읽기 타임아웃은 SDK 기본값(30초)이다. 호출별 예산은 요청의 `apiCallTimeout`이 따로 건다
 * (보정 요청 다듬기 20초) — 그보다 긴 호출이 생기면 여기서 늘린다.
 */
@Configuration
class AwsBedrockConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "true")
    fun bedrockRuntimeClient(
        credentialsProvider: AwsCredentialsProvider,
        properties: LlmProperties,
    ): BedrockRuntimeClient = BedrockRuntimeClient.builder()
        .credentialsProvider(credentialsProvider)
        .region(Region.of(properties.region))
        .httpClientBuilder(ApacheHttpClient.builder())
        .overrideConfiguration { it.retryStrategy(AwsRetryStrategy.doNotRetry()) }
        .build()
}
