package com.soma.wes.analysis.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.providers.AwsRegionProvider
import software.amazon.awssdk.services.ec2.Ec2Client

/**
 * GPU score 워커 인스턴스를 켜고 끄는 EC2 클라이언트. Lambda 클라이언트와 같이 Spring Cloud AWS 의 자격증명·리전 프로바이더를 받는다.
 * 쓰는 도메인이 `analysis` 하나라 여기 둔다 — 두 번째 도메인이 필요로 하는 날 `global/config` 로 올린다.
 */
@Configuration
class AwsEc2Config {

    @Bean
    fun ec2Client(
        credentialsProvider: AwsCredentialsProvider,
        regionProvider: AwsRegionProvider,
    ): Ec2Client = Ec2Client.builder()
        .credentialsProvider(credentialsProvider)
        .region(regionProvider.region)
        .build()
}
