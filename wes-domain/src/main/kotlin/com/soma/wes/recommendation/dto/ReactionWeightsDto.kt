package com.soma.wes.recommendation.dto

/**
 * 반응을 점수로 바꾸는 갤러리의 계수. 지금은 모든 갤러리가 [DEFAULT]이고, 갤러리별로 달라진 반응을 근거로
 * 계수를 고치는 날 [com.soma.wes.recommendation.support.ReactionWeightsProvider]가 그 값을 돌려준다(#391 ADR 0002).
 */
data class ReactionWeightsDto(
    /** 반응 가산의 최댓값(z 단위). */
    val rating: Double,
    /** 5점·4점 사진이 닮은 사진에 주는 무게. */
    val five: Double,
    val four: Double,
    /** 하객 좋아요의 무게. 별점과 같은 곡선으로 닮은 사진에 퍼진다. */
    val guestLike: Double,
    /** 닮은 다른 사진이 받는 몫의 최댓값 — 반응한 사진 자체(1)보다 늘 낮다. */
    val neighborMax: Double,
    /** 이보다 덜 닮으면(코사인) 다른 사진은 가산을 받지 않는다. */
    val similarityFloor: Double,
) {

    companion object {
        /** 반응 가산의 최댓값(z 단위). 점수가 평균 근처인 5점 사진을 상위 16% 쯤으로 올리는 크기다. */
        const val DEFAULT_RATING = 1.0

        /** 별점별 무게. 3점 이하는 가산하지 않는다. */
        const val DEFAULT_FIVE = 1.0
        const val DEFAULT_FOUR = 0.6

        /** 하객 좋아요의 무게 — 4점의 절반. 운영 52번에서 하객만 좋아한 40장은 담기지 않아(0장) 약하게 둔다. */
        const val DEFAULT_GUEST_LIKE = 0.3

        /**
         * 닮은 다른 사진의 몫 최댓값. 반응한 사진 자체 > 아주 닮음 > 그냥 닮음이 분명히 갈리게 한다 — 0.80 기준·최대 1이던 첫 곡선은
         * dev 갤러리 1942에서 코사인 0.93인 이웃이 5점 사진 자체의 2/3를 받아 묶음 대표 자리를 뺏었다(2026-11-08 작성자 결정).
         */
        const val DEFAULT_NEIGHBOR_MAX = 0.7

        /**
         * 이보다 덜 닮으면(코사인) 다른 사진은 가산을 받지 않는다. 같은 폴더(같은 장면)의 사진은 대부분 0.80을 넘는다 — dev 갤러리 1942의
         * 571장 폴더에서 5점 사진과 0.80 이상이 93%, 0.90 이상이 27%였다. 0.80이면 "닮은 사진"이 폴더 전체가 된다.
         */
        const val DEFAULT_SIMILARITY_FLOOR = 0.90

        val DEFAULT = ReactionWeightsDto(
            rating = DEFAULT_RATING,
            five = DEFAULT_FIVE,
            four = DEFAULT_FOUR,
            guestLike = DEFAULT_GUEST_LIKE,
            neighborMax = DEFAULT_NEIGHBOR_MAX,
            similarityFloor = DEFAULT_SIMILARITY_FLOOR,
        )
    }
}
