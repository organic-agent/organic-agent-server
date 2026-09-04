package com.soma.wes.recommendation.support

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

class MmrSelectorUnitTest {

    @Test
    fun `연사 클러스터당 최고점 1장만 후보로 남기고 폴더 안 순위를 매긴다`() {
        // given — 0·1은 같은 연사(1이 점수 높음), 2는 다른 클러스터, 3은 세 번째
        val score = doubleArrayOf(0.5, 0.9, 0.7, 0.1)
        val emb = arrayOf(unit(1f, 0f), unit(1f, 0f), unit(0f, 1f), unit(0.7f, 0.7f))
        val clusters = intArrayOf(1, 1, 2, 3)

        // when
        val picks = MmrSelector.selectInFolder(score, emb, members = listOf(0, 1, 2, 3), clusterIds = clusters, n = 2)

        // then — 1(연사 대표) 뒤에 0과 다른 방향인 2가 붙고, 순위는 점수순
        assertSoftly { softly ->
            softly.assertThat(picks.map { it.index }).containsExactly(1, 2)
            softly.assertThat(picks.map { it.folderRank }).containsExactly(1, 2)
            softly.assertThat(picks.map { it.quota }).containsOnly(2)
        }
    }

    @Test
    fun `MMR은 앞서 고른 것과 비슷한 방향을 밀어낸다`() {
        // given — 1은 0과 같은 방향이라 점수가 조금 높아도 뒤로 밀린다
        val score = doubleArrayOf(1.0, 0.95, 0.6)
        val emb = arrayOf(unit(1f, 0f), unit(1f, 0f), unit(0f, 1f))

        // when
        val picked = MmrSelector.mmrSelect(score, emb, k = 2, lambda = 0.7, candidates = listOf(0, 1, 2))

        // then
        assertThat(picked).containsExactly(0, 2)
    }

    private fun unit(x: Float, y: Float): FloatArray {
        val norm = kotlin.math.sqrt(x * x + y * y)
        return floatArrayOf(x / norm, y / norm)
    }
}
