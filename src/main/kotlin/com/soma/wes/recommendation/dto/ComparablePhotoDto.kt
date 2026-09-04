package com.soma.wes.recommendation.dto

import com.soma.wes.photo.domain.PhotoAnalysis

/** 비교샷 판정이 보는 사진 한 장의 수치. 엔티티에서 떼어 둔 것은 사실 수집·템플릿을 DB 없이 검증하기 위해서다. */
data class ComparablePhotoDto(
    val photoId: Long,
    val technicalPct: Double,
    val aestheticPct: Double,
    val sharpness: Double?,
    val highlightClip: Double?,
    val clusterId: Int?,
    val clusterRank: Int?,
    val subjects: String,
) {

    companion object {

        fun from(analysis: PhotoAnalysis) = ComparablePhotoDto(
            photoId = analysis.photoId,
            technicalPct = analysis.technicalPct?.toDouble() ?: 0.0,
            aestheticPct = analysis.aestheticPct?.toDouble() ?: 0.0,
            sharpness = analysis.subScore("sharpness"),
            highlightClip = analysis.subScore("highlight_clip"),
            clusterId = analysis.clusterId,
            clusterRank = analysis.clusterRank,
            subjects = analysis.subjects ?: "unknown",
        )
    }
}
