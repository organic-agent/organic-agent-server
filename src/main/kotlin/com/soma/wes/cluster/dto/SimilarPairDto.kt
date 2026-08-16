package com.soma.wes.cluster.dto

/**
 * 유사 쌍 질의가 돌려주는 간선 하나.
 *
 * id 쌍만이 아니라 거리와 시간 관계를 함께 담는다 — 후처리(시간 창 밖 간선의
 * 상호 kNN 필터)가 간선을 이웃 순위로 다시 재고, 창 밖이 실측된 간선만 필터 대상으로
 * 삼기 때문이다.
 */
data class SimilarPairDto(
    val leftId: Long,
    val rightId: Long,
    /** pgvector `<=>`가 잰 코사인 거리. 유사도 0.9 = 거리 0.1. */
    val distance: Double,
    val timeRelation: TimeRelation,
) {

    enum class TimeRelation {
        /** 두 촬영 시각이 모두 있고 차이가 시간 창 이하 — 체이닝이 안전한 같은 시간대. */
        WITHIN_WINDOW,

        /** 두 촬영 시각이 모두 있고 차이가 시간 창 초과 — 우연히 닮았을 확률이 높은 간선. */
        OUT_OF_WINDOW,

        /** 한쪽이라도 촬영 시각이 없어 멀고 가까움을 잴 수 없다. */
        UNKNOWN,
    }
}
