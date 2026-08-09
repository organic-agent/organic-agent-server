package com.soma.wes.gallery.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Mock 갤러리는 샘플 S3 객체와 manifest가 함께 준비된 배포에서만 켠다.
 *
 * 기본값을 false로 두어 코드만 먼저 배포돼도 존재하지 않는 샘플 key를 응답하지 않는다.
 * WES-22가 운영 자산을 업로드하고 manifest를 포함한 뒤 두 값을 함께 설정한다.
 */
@ConfigurationProperties(prefix = "app.mock-gallery")
data class MockGalleryProperties(
    val enabled: Boolean = false,
    val manifestLocation: String = "",
)
