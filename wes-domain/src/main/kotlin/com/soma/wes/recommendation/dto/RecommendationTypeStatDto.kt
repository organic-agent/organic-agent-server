package com.soma.wes.recommendation.dto


data class RecommendationTypeStatDto(
    val poolShare: Double,
    val deficit: Double,
    val selectLift: Double,
    val poolCount: Int,
    val selCount: Int,
)
