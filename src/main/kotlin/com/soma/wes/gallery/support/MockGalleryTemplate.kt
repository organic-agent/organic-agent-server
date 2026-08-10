package com.soma.wes.gallery.support

/** 배포 artifact에 포함되는 버전 고정 Mock 갤러리 manifest. */
data class MockGalleryTemplate(
    val schemaVersion: Int,
    val templateVersion: String,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val photos: List<MockGalleryTemplatePhoto>,
)
