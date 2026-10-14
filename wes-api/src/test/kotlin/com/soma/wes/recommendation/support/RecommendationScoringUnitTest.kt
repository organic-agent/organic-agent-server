package com.soma.wes.recommendation.support

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

class RecommendationScoringUnitTest {

    @Test
    fun `담은 사진이 신랑 쪽으로 쏠리면 신부 단독 컷에만 가산점을 준다`() {
        // given — 담은 사진: 신랑 3, 신부 1, 부부 1
        val types = listOf("groom", "groom", "groom", "bride", "couple", "bride", "groom", "couple")
        val selected = listOf(0, 1, 2, 3, 4)

        // when
        val bonus = RecommendationScoring.spouseBalance(types, selected)

        // then — 쏠림 (3 − 1) / 4 = 0.5 → 신부 0.25, 신랑·부부 0
        assertSoftly { softly ->
            softly.assertThat(bonus[5]).isEqualTo(RecommendationScoring.W_SPOUSE_BALANCE * 0.5)
            softly.assertThat(bonus[6]).isZero()
            softly.assertThat(bonus[7]).isZero()
        }
    }

    @Test
    fun `신랑·신부 단독 컷을 하나도 안 담았으면 가산점이 없다`() {
        // given
        val types = listOf("couple", "bride", "groom")

        // when
        val bonus = RecommendationScoring.spouseBalance(types, listOf(0))

        // then
        assertThat(bonus.toList()).containsOnly(0.0)
    }
}
