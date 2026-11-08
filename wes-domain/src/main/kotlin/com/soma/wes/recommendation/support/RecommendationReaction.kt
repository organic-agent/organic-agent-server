package com.soma.wes.recommendation.support

import com.soma.wes.photo.domain.PhotoRating
import com.soma.wes.recommendation.dto.ReactionWeightsDto

/**
 * 부부·하객 반응을 추천 점수에 얹는 층 — 갤러리 안에서만, 추천 때마다 계산한다(#391).
 *
 *     1점:   그 사진만 후보에서 뺀다. 이웃은 건드리지 않는다
 *     반응 h: 4~5점(무게 5점·4점)과 하객 좋아요(무게 guestLike) — 한 사진에 둘이면 큰 무게
 *     가산(p) = rating · max_h( w(h) · f(p, h) )
 *     f(p, h) = 1                                              p = h (반응한 사진 자체)
 *             = neighborMax · clamp((cos − floor) / (1 − floor), 0, 1)   그 밖 — 아주 닮을수록 크고, 자체보다 늘 작다
 *
 * 1점에 이웃 감점을 두지 않는 이유: 운영 52번에서 담은 50장 중 24장이 1점이 섞인 연사에 있었다 — 1점은 "그 장면이 싫다"보다
 * "그 컷의 결함(눈·표정)"으로 읽힌다. 4~5점 이웃 가산의 근거는 적중률이 아니라 신뢰다 — "내가 높게 준 사진과 닮은 사진을 AI가
 * 추천한다"(작성자 결정 2026-11-08). 여러 반응 중 가장 큰 하나만 쓰는 것은, 합하면 반응이 많은 장면이 끝없이 오르기 때문이다.
 * 자체 > 아주 닮음 > 그냥 닮음이 분명히 갈려야 한다(작성자 결정 2026-11-08) — 추천의 일은 반응한 사진과 닮은 다른 사진을 찾는 것이지만,
 * 반응한 사진 자체가 가장 높아야 신뢰가 선다. 하객 좋아요는 부부 본인의 행동이 아니라 무게를 작게 두고 같은 곡선을 탄다.
 * 계수는 갤러리마다 [ReactionWeightsDto]로 받는다.
 * 임베딩은 정규화돼 있다고 본다(내적 = 코사인). 벡터가 비어 있는 행(범위 밖)은 0이다.
 */
object RecommendationReaction {

    /** 후보에서 뺄 별점 — 1점. */
    const val EXCLUDED_SCORE = PhotoRating.MIN_SCORE

    /** 별점 → 이웃 가산 무게. 4~5점만 값이 있다. */
    fun likeWeight(score: Int, weights: ReactionWeightsDto): Double? = when (score) {
        PhotoRating.MAX_SCORE -> weights.five
        PhotoRating.MAX_SCORE - 1 -> weights.four
        else -> null
    }

    /**
     * [anchors]는 반응한 사진의 행 인덱스 → 무게(별점·하객 좋아요 중 큰 것)다. 반환은 [emb]와 같은 인덱스의 가산점이다.
     * 닮음은 벡터가 있는 행끼리만 잰다 — 반응한 사진이 범위 밖이면 호출자가 그 벡터도 읽어 둬야 한다.
     */
    fun boost(emb: Array<FloatArray>, anchors: Map<Int, Double>, weights: ReactionWeightsDto): DoubleArray {
        val usable = anchors.filterKeys { emb[it].isNotEmpty() }
        if (usable.isEmpty()) return DoubleArray(emb.size)
        return DoubleArray(emb.size) { i ->
            if (emb[i].isEmpty()) return@DoubleArray 0.0
            weights.rating * usable.maxOf { (h, weight) -> weight * closeness(emb, i, h, weights) }
        }
    }

    private fun closeness(emb: Array<FloatArray>, i: Int, h: Int, weights: ReactionWeightsDto): Double {
        if (i == h) return 1.0
        val floor = weights.similarityFloor
        val scaled = (dot(emb[i], emb[h]) - floor) / (1 - floor)
        return weights.neighborMax * scaled.coerceIn(0.0, 1.0)
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (k in a.indices) sum += a[k] * b[k]
        return sum
    }
}
