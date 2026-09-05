package com.soma.wes.recommendation.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class FolderFitRuleUnitTest {

    @Test
    fun `폴더 안 다른 사진들과 동떨어진 한 장을 고른다`() {
        // given — 0~5는 (1,0) 근처에 몰려 있고 6은 직각 방향
        val emb = arrayOf(
            unit(1f, 0f), unit(1f, 0.05f), unit(1f, -0.05f), unit(1f, 0.1f), unit(1f, -0.1f), unit(1f, 0.02f),
            unit(0f, 1f),
        )

        // when
        val misfit = FolderFitRule.misfits(emb, members = emb.indices.toList())

        // then
        assertThat(misfit).containsExactly(6)
    }

    @Test
    fun `전부 비슷하면 아무것도 빼지 않는다`() {
        // given — 흔들림이 MIN_GAP 안이다
        val emb = arrayOf(unit(1f, 0f), unit(1f, 0.05f), unit(1f, -0.05f), unit(1f, 0.1f), unit(1f, -0.1f), unit(1f, 0.15f))

        // when
        val misfit = FolderFitRule.misfits(emb, members = emb.indices.toList())

        // then
        assertThat(misfit).isEmpty()
    }

    @Test
    fun `작은 폴더는 판정하지 않는다`() {
        // given — 5장, 하나는 직각 방향이지만 MIN_FOLDER_SIZE 미만
        val emb = arrayOf(unit(1f, 0f), unit(1f, 0f), unit(1f, 0f), unit(1f, 0f), unit(0f, 1f))

        // when
        val misfit = FolderFitRule.misfits(emb, members = emb.indices.toList())

        // then
        assertThat(misfit).isEmpty()
    }

    @Test
    fun `동떨어진 사진이 너무 많으면 폴더가 섞인 것으로 보고 빼지 않는다`() {
        // given — 6장 중 3장이 다른 방향. 잘못 든 사진이 아니라 두 장면이 한 폴더에 든 것이다
        val emb = arrayOf(unit(1f, 0f), unit(1f, 0f), unit(1f, 0f), unit(0f, 1f), unit(0f, 1f), unit(0f, 1f))

        // when
        val misfit = FolderFitRule.misfits(emb, members = emb.indices.toList())

        // then
        assertThat(misfit).isEmpty()
    }

    @Test
    fun `members에 든 인덱스만 보고 그 인덱스로 돌려준다`() {
        // given — 0은 폴더 밖 사진이라 중심 계산에 끼지 않는다
        val emb = arrayOf(
            unit(0f, 1f),
            unit(1f, 0f), unit(1f, 0.05f), unit(1f, -0.05f), unit(1f, 0.1f), unit(1f, -0.1f), unit(1f, 0.02f),
            unit(0f, 1f),
        )

        // when
        val misfit = FolderFitRule.misfits(emb, members = (1..7).toList())

        // then
        assertThat(misfit).containsExactly(7)
    }

    private fun unit(x: Float, y: Float): FloatArray {
        val norm = sqrt(x * x + y * y)
        return floatArrayOf(x / norm, y / norm)
    }
}
