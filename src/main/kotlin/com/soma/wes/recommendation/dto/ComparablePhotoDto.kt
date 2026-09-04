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

        /** 호출 전에 [PhotoAnalysis.isAnalyzed]로 걸러야 한다 — 백분위 없는 행을 기본값으로 메꾸지 않는다. */
        fun from(analysis: PhotoAnalysis) = ComparablePhotoDto(
            photoId = analysis.photoId,
            technicalPct = requireNotNull(analysis.technicalPct) { "분석되지 않은 사진: ${analysis.photoId}" }.toDouble(),
            aestheticPct = requireNotNull(analysis.aestheticPct) { "분석되지 않은 사진: ${analysis.photoId}" }.toDouble(),
            sharpness = analysis.subScore("sharpness"),
            highlightClip = analysis.subScore("highlight_clip"),
            clusterId = analysis.clusterId,
            clusterRank = analysis.clusterRank,
            subjects = analysis.subjects ?: "unknown",
        )
    }
}
