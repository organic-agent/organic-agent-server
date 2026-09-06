package com.soma.wes.recommendation.dto

import com.soma.wes.photo.repository.projection.PhotoAnalysisSummary

/** 추천 계산이 보는 사진 한 장 — 분석 행에서 숫자만 뗀 것. 계산 코드는 엔티티·DB를 모른다. */
data class RecommendablePhotoDto(
    val photoId: Long,
    val technicalPct: Double,
    val aestheticPct: Double,
    val subjects: String,
    val clusterId: Int,
    val clusterRank: Int,
    val subScores: Map<String, Any?>,
) {

    fun subScore(key: String): Double? = (subScores[key] as? Number)?.toDouble()

    companion object {

        /** [summary]는 분석이 끝난 행이어야 한다 — 조회 쿼리가 백분위 없는 행을 거른다. */
        fun from(summary: PhotoAnalysisSummary) = RecommendablePhotoDto(
            photoId = summary.photoId,
            technicalPct = summary.technicalPct.toDouble(),
            aestheticPct = summary.aestheticPct.toDouble(),
            subjects = summary.subjects ?: "unknown",
            clusterId = summary.clusterId ?: -1,
            clusterRank = summary.clusterRank ?: 0,
            subScores = summary.subScores,
        )
    }
}
