package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.domain.PhotoSubject.BRIDE
import com.soma.wes.recommendation.domain.PhotoSubject.COUPLE
import com.soma.wes.recommendation.domain.PhotoSubject.GROOM
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

class RecommendationScoringUnitTest {

    @Test
    fun `담은 사진이 신랑 쪽으로 쏠리면 신부 단독 컷에만 가산점을 준다`() {
        // given — 담은 사진: 신랑 3, 신부 1, 부부 1
        val types = listOf(GROOM, GROOM, GROOM, BRIDE, COUPLE, BRIDE, GROOM, COUPLE)
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
    fun `추천 기본 점수가 사진 전부에 있으면 그 값을 기본 점수로 쓴다`() {
        // given
        val technical = doubleArrayOf(90.0, 10.0)
        val aesthetic = doubleArrayOf(80.0, 20.0)

        // when
        val prior = RecommendationScoring.prior(technical, aesthetic, listOf(5.0, 95.0))

        // then
        assertThat(prior.toList()).containsExactly(5.0, 95.0)
    }

    @Test
    fun `추천 기본 점수가 한 장이라도 없으면 전부 화질·미학 백분위로 계산한다`() {
        // given
        val technical = doubleArrayOf(90.0, 10.0)
        val aesthetic = doubleArrayOf(80.0, 20.0)

        // when
        val partial = RecommendationScoring.prior(technical, aesthetic, listOf(5.0, null))
        val none = RecommendationScoring.prior(technical, aesthetic, listOf(null, null))

        // then
        assertSoftly { softly ->
            softly.assertThat(partial.toList()).containsExactly(85.0, 15.0)
            softly.assertThat(none.toList()).containsExactly(85.0, 15.0)
        }
    }

    @Test
    fun `신랑·신부 단독 컷을 하나도 안 담았으면 가산점이 없다`() {
        // given
        val types = listOf(COUPLE, BRIDE, GROOM)

        // when
        val bonus = RecommendationScoring.spouseBalance(types, listOf(0))

        // then
        assertThat(bonus.toList()).containsOnly(0.0)
    }
}
