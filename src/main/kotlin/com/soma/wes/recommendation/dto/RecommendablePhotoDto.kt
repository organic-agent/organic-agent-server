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

        /** 호출 전에 [PhotoAnalysis.isAnalyzed]로 걸러야 한다 — 백분위 없는 행을 기본값으로 메꾸지 않는다. */
        fun from(analysis: PhotoAnalysis) = RecommendablePhotoDto(
            photoId = analysis.photoId,
            technicalPct = requireNotNull(analysis.technicalPct) { "분석되지 않은 사진: ${analysis.photoId}" }.toDouble(),
            aestheticPct = requireNotNull(analysis.aestheticPct) { "분석되지 않은 사진: ${analysis.photoId}" }.toDouble(),
            subjects = analysis.subjects ?: "unknown",
            clusterId = analysis.clusterId ?: -1,
            clusterRank = analysis.clusterRank ?: 0,
            subScores = analysis.subScores,
        )
    }
}
