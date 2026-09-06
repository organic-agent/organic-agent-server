package com.soma.wes.photo.repository.projection

/**
 * 분석 행에서 벡터를 뺀 것 — 추천 계산이 갤러리 전체를 보는 데 필요한 컬럼만 담는다.
 * 점수 정규화·피사체 통계·연사 형제 목록은 전체 행이 필요하지만 벡터는 아니다. 갤러리가 수천 장이면
 * 벡터 둘(768차원 × 2)이 읽기 시간의 대부분이라 여기서 뗀다.
 */
interface PhotoAnalysisSummary {
    val photoId: Long
    val technicalPct: Float
    val aestheticPct: Float
    val subjects: String?
    val clusterId: Int?
    val clusterRank: Int?
    val subScores: Map<String, Any?>
}
