package com.soma.wes.recommendation.support

import kotlin.math.sqrt
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

class RecommendationReactionUnitTest {

    @Test
    fun `4~5점 사진과 닮은 만큼 가산하고 0_80 아래는 0, 사진 자체는 최댓값이다`() {
        // given — 0: 5점 사진, 1: 코사인 0.9, 2: 코사인 0.7, 3: 벡터 없음(범위 밖)
        val emb = arrayOf(unit(1.0, 0.0), unit(0.9, sqrt(1 - 0.81)), unit(0.7, sqrt(1 - 0.49)), FloatArray(0))

        // when
        val boost = RecommendationReaction.ratingBoost(emb, mapOf(0 to RecommendationReaction.WEIGHT_FIVE))

        // then — (0.9 − 0.8) / 0.2 = 0.5
        assertSoftly { softly ->
            softly.assertThat(boost[0]).isEqualTo(RecommendationReaction.W_RATING)
            softly.assertThat(boost[1]).isCloseTo(0.5, within(1e-6))
            softly.assertThat(boost[2]).isZero()
            softly.assertThat(boost[3]).isZero()
        }
    }

    @Test
    fun `여러 4~5점 사진 중 가장 큰 한 항만 쓴다`() {
        // given — 0: 4점, 1: 5점, 2: 둘 모두와 같은 방향
        val emb = arrayOf(unit(1.0, 0.0), unit(1.0, 0.0), unit(1.0, 0.0))

        // when
        val boost = RecommendationReaction.ratingBoost(
            emb,
            mapOf(0 to RecommendationReaction.WEIGHT_FOUR, 1 to RecommendationReaction.WEIGHT_FIVE),
        )

        // then — 합(1.6)이 아니라 최댓값(1.0)
        assertThat(boost[2]).isCloseTo(RecommendationReaction.WEIGHT_FIVE, within(1e-6))
    }

    @Test
    fun `4~5점만 무게가 있고 별점이 없으면 가산도 없다`() {
        // given
        val emb = arrayOf(unit(1.0, 0.0), unit(0.0, 1.0))

        // when
        val boost = RecommendationReaction.ratingBoost(emb, emptyMap())

        // then
        assertSoftly { softly ->
            softly.assertThat((1..3).map { RecommendationReaction.likeWeight(it) }).containsOnlyNulls()
            softly.assertThat(RecommendationReaction.likeWeight(4)).isEqualTo(RecommendationReaction.WEIGHT_FOUR)
            softly.assertThat(RecommendationReaction.likeWeight(5)).isEqualTo(RecommendationReaction.WEIGHT_FIVE)
            softly.assertThat(boost.toList()).containsOnly(0.0)
        }
    }

    private fun unit(x: Double, y: Double) = floatArrayOf(x.toFloat(), y.toFloat())
}
