package com.soma.wes.recommendation.dto


data class RecommendationCombinedScoreDto(
    val score: DoubleArray,
    val priorZ: DoubleArray,
    val balanceZ: DoubleArray,
    val affinityZ: DoubleArray,
)
