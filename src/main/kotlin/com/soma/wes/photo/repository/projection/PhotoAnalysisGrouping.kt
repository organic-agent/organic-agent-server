package com.soma.wes.photo.repository.projection

/**
 * 폴더 계획이 보는 분석 컬럼 — 임베딩 그룹·피사체·연사 번호. AI 폴더 실체화가 갤러리 전체를 읽을 때
 * 벡터 둘(768차원 × 2)을 나르지 않으려고 뗀 것이다. 분석이 안 끝난 행(그룹 null)도 포함한다 — 그런 사진은 "기타"로 간다.
 */
interface PhotoAnalysisGrouping {
    val photoId: Long
    val embedGroupId: Int?
    val subjects: String?
    val clusterId: Int?
}
