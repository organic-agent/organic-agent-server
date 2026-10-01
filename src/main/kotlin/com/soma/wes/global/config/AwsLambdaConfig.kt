package com.soma.wes.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.providers.AwsRegionProvider
import software.amazon.awssdk.services.lambda.LambdaClient

/**
 * `S3Presigner`와 달리 Lambda 클라이언트는 Spring Cloud AWS가 만들어 주지 않는다(스타터가 없다).
 *
 * 자격증명·리전은 다른 AWS 클라이언트와 같은 소스를 써야 하므로 직접 만들지 않고,
 * Spring Cloud AWS가 이미 노출한 프로바이더 빈을 그대로 받아 넘긴다.
 *
 * `embedding/config`에서 여기로 온 이유: 처음에는 임베딩 호출 하나만 쓰다가 두 번째 도메인이 붙으면서
 * 도메인 공용 규칙(두 번째 도메인이 필요로 하는 날 `global`로)을 따랐다. 지금 사용처는
 * [com.soma.wes.analysis.infrastructure.LambdaAiTaskSender] 하나다.
 */
@Configuration
class AwsLambdaConfig {

    @Bean
    fun lambdaClient(
        credentialsProvider: AwsCredentialsProvider,
        regionProvider: AwsRegionProvider,
    ): LambdaClient = LambdaClient.builder()
        .credentialsProvider(credentialsProvider)
        .region(regionProvider.region)
        .build()
}