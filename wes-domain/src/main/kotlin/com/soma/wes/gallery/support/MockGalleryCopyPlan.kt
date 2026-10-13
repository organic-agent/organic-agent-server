package com.soma.wes.gallery.support

import com.soma.wes.photo.domain.Photo

/**
 * 템플릿 사진 한 장의 복제 계획 — 원본 행과, 새 갤러리 키 공간의 목적지.
 *
 * [previewKey]가 null인 것은 원본에 파생본이 없다는 뜻이다. 그 사진은 원본만 복사하고
 * 복제된 행도 previewKey 없이 남는다(viewKey가 원본으로 폴백하는 일반 의미론 그대로).
 */
data class MockGalleryCopyPlan(
    val source: Photo,
    val storageKey: String,
    val previewKey: String?,
)
