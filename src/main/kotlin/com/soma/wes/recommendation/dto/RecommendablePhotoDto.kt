package com.soma.wes.recommendation.dto

import com.soma.wes.photo.domain.PhotoAnalysis

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

        fun from(analysis: PhotoAnalysis) = RecommendablePhotoDto(
            photoId = analysis.photoId,
            technicalPct = analysis.technicalPct?.toDouble() ?: 50.0,
            aestheticPct = analysis.aestheticPct?.toDouble() ?: 50.0,
            subjects = analysis.subjects ?: "unknown",
            clusterId = analysis.clusterId ?: -1,
            clusterRank = analysis.clusterRank ?: 0,
            subScores = analysis.subScores,
        )
    }
}
