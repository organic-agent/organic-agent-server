package com.soma.wes.analysis.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.providers.AwsRegionProvider
import software.amazon.awssdk.services.sqs.SqsClient

/**
 * 분석 Lambda 실패 큐를 읽는 SQS 클라이언트(#286 (c)). 큐 주소가 있을 때만 만든다 — 로컬·테스트·관리자 앱에는 없다.
 * 쓰는 도메인이 `analysis` 하나라 여기 둔다.
 */
@Configuration
@ConditionalOnExpression("!'\${app.analysis.failure-queue.queue-url:}'.isBlank()")
class AwsSqsConfig {

    @Bean
    fun sqsClient(
        credentialsProvider: AwsCredentialsProvider,
        regionProvider: AwsRegionProvider,
    ): SqsClient = SqsClient.builder()
        .credentialsProvider(credentialsProvider)
        .region(regionProvider.region)
        .build()
}
