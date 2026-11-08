package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.ReactionWeightsDto
import kotlin.math.sqrt
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

class RecommendationReactionUnitTest {

    private val weights = ReactionWeightsDto.DEFAULT

    @Test
    fun `반응한 사진 자체가 가장 높고 아주 닮을수록 높게, 0_90 아래는 0이다`() {
        // given — 0: 5점 사진, 1: 코사인 0.97, 2: 0.95, 3: 0.88, 4: 벡터 없음(범위 밖)
        val emb = arrayOf(unit(1.0), unit(0.97), unit(0.95), unit(0.88), FloatArray(0))

        // when
        val boost = RecommendationReaction.boost(emb, mapOf(0 to weights.five), weights)

        // then — 0.7 × (cos − 0.9) / 0.1
        assertSoftly { softly ->
            softly.assertThat(boost[0]).isEqualTo(weights.rating)
            softly.assertThat(boost[1]).isCloseTo(0.49, within(1e-6))
            softly.assertThat(boost[2]).isCloseTo(0.35, within(1e-6))
            softly.assertThat(boost[3]).isZero()
            softly.assertThat(boost[4]).isZero()
            softly.assertThat(boost[1]).isLessThan(boost[0])
        }
    }

    @Test
    fun `여러 반응 중 가장 큰 한 항만 쓴다`() {
        // given — 0: 4점, 1: 5점, 2: 둘 모두와 같은 방향(코사인 1)
        val emb = arrayOf(unit(1.0), unit(1.0), unit(1.0))

        // when
        val boost = RecommendationReaction.boost(emb, mapOf(0 to weights.four, 1 to weights.five), weights)

        // then — 합(0.42 + 0.7)이 아니라 최댓값(5점 × 이웃 최대 0.7)
        assertThat(boost[2]).isCloseTo(weights.five * weights.neighborMax, within(1e-6))
    }

    @Test
    fun `하객 좋아요도 같은 곡선으로 그 사진이 가장 높고 닮은 사진에 약하게 퍼진다`() {
        // given — 0: 하객 좋아요, 1: 코사인 0.97
        val emb = arrayOf(unit(1.0), unit(0.97))

        // when
        val boost = RecommendationReaction.boost(emb, mapOf(0 to weights.guestLike), weights)

        // then
        assertSoftly { softly ->
            softly.assertThat(boost[0]).isCloseTo(weights.rating * weights.guestLike, within(1e-6))
            softly.assertThat(boost[1]).isCloseTo(weights.guestLike * 0.49, within(1e-6))
        }
    }

    @Test
    fun `4~5점만 무게가 있고 반응이 없으면 가산도 없다`() {
        // given
        val emb = arrayOf(unit(1.0), unit(0.0))

        // when
        val boost = RecommendationReaction.boost(emb, emptyMap(), weights)

        // then
        assertSoftly { softly ->
            softly.assertThat((1..3).map { RecommendationReaction.likeWeight(it, weights) }).containsOnlyNulls()
            softly.assertThat(RecommendationReaction.likeWeight(4, weights)).isEqualTo(weights.four)
            softly.assertThat(RecommendationReaction.likeWeight(5, weights)).isEqualTo(weights.five)
            softly.assertThat(boost.toList()).containsOnly(0.0)
        }
    }

    /** 첫 축과 코사인이 [cos]인 단위 벡터. */
    private fun unit(cos: Double) = floatArrayOf(cos.toFloat(), sqrt(1 - cos * cos).toFloat())
}
