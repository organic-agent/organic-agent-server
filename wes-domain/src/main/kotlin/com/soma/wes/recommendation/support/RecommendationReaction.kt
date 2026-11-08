package com.soma.wes.recommendation.support

import com.soma.wes.photo.domain.PhotoRating

/**
 * 부부 반응(별점)을 추천 점수에 얹는 층 — 갤러리 안에서만, 추천 때마다 계산한다(#391).
 *
 *     1점: 그 사진만 후보에서 뺀다. 이웃은 건드리지 않는다
 *     4~5점: 닮은 사진에 닮은 만큼 가산 — rating(p) = W_RATING · max_h( w(h) · sim(p, h) ),  사진 자체는 sim = 1
 *            sim = clamp((cos − SIMILARITY_FLOOR) / (1 − SIMILARITY_FLOOR), 0, 1)
 *
 * 1점에 이웃 감점을 두지 않는 이유: 운영 52번에서 담은 50장 중 24장이 1점이 섞인 연사에 있었다 — 1점은 "그 장면이 싫다"보다
 * "그 컷의 결함(눈·표정)"으로 읽힌다. 4~5점 이웃 가산의 근거는 적중률이 아니라 신뢰다 — "내가 높게 준 사진과 닮은 사진을 AI가
 * 추천한다"(작성자 결정 2026-11-08). 여러 4~5점 중 가장 닮은 하나만 쓰는 것은, 합하면 4~5점이 많은 장면이 끝없이 오르기 때문이다.
 * 임베딩은 정규화돼 있다고 본다(내적 = 코사인). 벡터가 비어 있는 행(범위 밖)은 0이다.
 */
object RecommendationReaction {

    /** 별점 가산의 최댓값(z 단위). 점수가 평균 근처인 5점 사진을 상위 16% 쯤으로 올리는 크기다. */
    const val W_RATING = 1.0

    /** 별점별 무게. 3점 이하는 가산하지 않는다. */
    const val WEIGHT_FIVE = 1.0
    const val WEIGHT_FOUR = 0.6

    /** 이보다 덜 닮으면(코사인) 가산하지 않는다. 운영 52번에서 4~5점과 0.80 아래인 사진은 담기는 비율이 기준과 같았다. */
    const val SIMILARITY_FLOOR = 0.80

    /** 후보에서 뺄 별점 — 1점. */
    const val EXCLUDED_SCORE = PhotoRating.MIN_SCORE

    /** 별점 → 가산 무게. 4~5점만 값이 있다. */
    fun likeWeight(score: Int): Double? = when (score) {
        PhotoRating.MAX_SCORE -> WEIGHT_FIVE
        PhotoRating.MAX_SCORE - 1 -> WEIGHT_FOUR
        else -> null
    }

    /**
     * [liked]는 4~5점 사진의 행 인덱스 → 무게다. 반환은 [emb]와 같은 인덱스의 가산점이다.
     * 가산은 벡터가 있는 행끼리만 잰다 — 4~5점 사진이 범위 밖이면 호출자가 그 벡터도 읽어 둬야 닮음을 잴 수 있다.
     */
    fun ratingBoost(emb: Array<FloatArray>, liked: Map<Int, Double>): DoubleArray {
        val anchors = liked.filterKeys { emb[it].isNotEmpty() }
        if (anchors.isEmpty()) return DoubleArray(emb.size)
        return DoubleArray(emb.size) { i ->
            if (emb[i].isEmpty()) {
                0.0
            } else {
                W_RATING * anchors.maxOf { (h, weight) -> weight * similarity(emb, i, h) }
            }
        }
    }

    private fun similarity(emb: Array<FloatArray>, i: Int, h: Int): Double {
        if (i == h) return 1.0
        val scaled = (dot(emb[i], emb[h]) - SIMILARITY_FLOOR) / (1 - SIMILARITY_FLOOR)
        return scaled.coerceIn(0.0, 1.0)
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (k in a.indices) sum += a[k] * b[k]
        return sum
    }
}
