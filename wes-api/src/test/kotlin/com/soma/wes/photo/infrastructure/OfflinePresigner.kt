package com.soma.wes.photo.infrastructure

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.presigner.S3Presigner

/**
 * 네트워크 없이 서명만 하는 진짜 SDK 프리사이너. 서명 URL 캐시가 SDK가 만든 URL 모양에 기대므로, 그 테스트는 가짜 URL이 아니라
 * 실제 SDK 결과로 돌아야 한다. 보안 토큰은 EC2 역할 토큰과 비슷한 길이(≈ 1,100자)로 둔다.
 */
object OfflinePresigner {

    fun create(): S3Presigner = S3Presigner.builder()
        .region(Region.AP_NORTHEAST_2)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsSessionCredentials.create(
                    "ASIAOFFLINEEXAMPLE01",
                    "offline-secret-offline-secret-offline-1234",
                    "IQoJb3JpZ2luX2Vj" + "A".repeat(1100),
                ),
            ),
        )
        .build()
}
