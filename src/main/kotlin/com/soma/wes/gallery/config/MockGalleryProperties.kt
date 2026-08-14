package com.soma.wes.gallery.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Mock 갤러리가 복제할 샘플 템플릿 갤러리의 위치.
 *
 * 템플릿 갤러리는 운영자 스튜디오가 일반 파이프라인(업로드 + 임베딩 Lambda)으로 만든
 * 진짜 갤러리다. 별도 manifest나 스키마 없이 갤러리 id 하나만 있으면 되고, 이 값이
 * 없는 환경(로컬·시드 전 운영)에서는 Mock 갤러리 생성이 503으로 막힌다.
 */
@ConfigurationProperties(prefix = "app.mock-gallery")
data class MockGalleryProperties(

    /** 복제 원본이 되는 템플릿 갤러리 id. 0이면 기능이 꺼진 것이다. */
    val templateGalleryId: Long = 0,
) {

    val isConfigured: Boolean
        get() = templateGalleryId > 0
}
