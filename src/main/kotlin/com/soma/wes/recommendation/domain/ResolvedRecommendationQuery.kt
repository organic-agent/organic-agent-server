package com.soma.wes.recommendation.domain

/** 워커가 한 번 해석한 조건. 재시작해도 같은 범위와 장수를 사용한다. null 범위는 갤러리 전체다. */
data class ResolvedRecommendationQuery(
    val detailFolderIds: List<Long>? = null,
    val targetCount: Int? = null,
)
