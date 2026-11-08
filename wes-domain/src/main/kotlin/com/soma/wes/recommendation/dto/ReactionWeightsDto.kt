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
    /** 하객 좋아요가 그 사진 자체에 주는 무게(이웃에는 퍼뜨리지 않는다). */
    val guestLike: Double,
    /** 이보다 덜 닮으면(코사인) 별점 가산을 주지 않는다. */
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

        /** 이보다 덜 닮으면(코사인) 가산하지 않는다. 운영 52번에서 4~5점과 0.80 아래인 사진은 담기는 비율이 기준과 같았다. */
        const val DEFAULT_SIMILARITY_FLOOR = 0.80

        val DEFAULT = ReactionWeightsDto(
            rating = DEFAULT_RATING,
            five = DEFAULT_FIVE,
            four = DEFAULT_FOUR,
            guestLike = DEFAULT_GUEST_LIKE,
            similarityFloor = DEFAULT_SIMILARITY_FLOOR,
        )
    }
}
