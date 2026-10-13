package com.soma.wes.recommendation.dto

import com.soma.wes.photo.domain.SubScoreKey
import com.soma.wes.photo.repository.projection.PhotoAnalysisSummary
import java.time.LocalDateTime

/** 추천 계산이 보는 사진 한 장 — 분석 행에서 숫자만 뗀 것. 계산 코드는 엔티티·DB를 모른다. */
data class RecommendablePhotoDto(
    val photoId: Long,
    val technicalPct: Double,
    val aestheticPct: Double,
    val subjects: String,
    // [GLOSSARY-1 2026-09-27] clusterId → burstId (용어집: 연사)
    val burstId: Int,
    /** 연사 안 대표 후보 순위(0 = 대표). 촬영 순서가 아니다 — 촬영 순서는 [takenAt]. */
    val burstRank: Int,
    val subScores: Map<String, Any?>,
    /** 촬영 시각(EXIF). 연사를 촬영 순서로 늘어놓을 때 쓴다. 없으면 null. */
    val takenAt: LocalDateTime? = null,
) {

    fun subScore(key: SubScoreKey): Double? = (subScores[key.key] as? Number)?.toDouble()

    companion object {

        /** [summary]는 분석이 끝난 행이어야 한다 — 조회 쿼리가 백분위 없는 행을 거른다. */
        fun from(summary: PhotoAnalysisSummary, takenAt: LocalDateTime?) = RecommendablePhotoDto(
            photoId = summary.photoId,
            technicalPct = summary.technicalPct.toDouble(),
            aestheticPct = summary.aestheticPct.toDouble(),
            subjects = summary.subjects ?: "unknown",
            burstId = summary.burstId ?: -1,
            burstRank = summary.burstRank ?: 0,
            subScores = summary.subScores,
            takenAt = takenAt,
        )
    }
}
