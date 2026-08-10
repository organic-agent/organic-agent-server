package com.soma.wes.gallery.support

data class MockGalleryTemplatePhoto(
    val storageKey: String,
    val previewKey: String,
    val originalFileName: String,
    val contentType: String,
    val displayOrder: Int,
    /** WES-22 업로드 시 manifest와 원본 객체가 같은 파일인지 검증하기 위한 값. */
    val originalSha256: String,
    /** WES-22 업로드 시 manifest와 미리보기 객체가 같은 파일인지 검증하기 위한 값. */
    val previewSha256: String,
    val embedding: List<Float>,
)
