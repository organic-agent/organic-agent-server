package com.soma.wes.cluster.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UnionFindTest {

    /** 같은 뿌리를 가진 것끼리 묶어 크기순으로 돌려준다. 클러스터 서비스가 하는 것과 같은 계산. */
    private fun groups(unionFind: UnionFind, size: Int): List<List<Int>> =
        (0 until size).groupBy { unionFind.find(it) }
            .values
            .sortedByDescending { it.size }

    @Test
    fun `아무것도 잇지 않으면 저마다 혼자 남는다`() {
        // 임계값이 너무 높아 닮은 쌍이 하나도 없는 경우다. 사진마다 크기 1짜리 묶음이 된다.
        // given
        val unionFind = UnionFind(3)

        // when & then
        assertThat(groups(unionFind, 3)).isEqualTo(listOf(listOf(0), listOf(1), listOf(2)))
    }

    @Test
    fun `직접 닮지 않아도 사이에 낀 것이 있으면 한 묶음이 된다`() {
        // 이 성질이 연결 요소를 쓰는 이유다. A-B가 닮고 B-C가 닮으면 A-C가 임계값에
        // 못 미쳐도 셋이 같은 인물·장면일 가능성이 높다.
        // given
        val unionFind = UnionFind(3)

        // when
        unionFind.union(0, 1)
        unionFind.union(1, 2)

        // then
        assertThat(unionFind.find(2)).isEqualTo(unionFind.find(0))
        assertThat(groups(unionFind, 3)).isEqualTo(listOf(listOf(0, 1, 2)))
    }

    @Test
    fun `이어지지 않은 덩어리는 따로 남는다`() {
        // given
        val unionFind = UnionFind(5)

        // when
        unionFind.union(0, 1)
        unionFind.union(1, 2)
        unionFind.union(3, 4)

        // then
        assertThat(unionFind.find(3)).isNotEqualTo(unionFind.find(0))
        assertThat(groups(unionFind, 5)).isEqualTo(listOf(listOf(0, 1, 2), listOf(3, 4)))
    }

    @Test
    fun `같은 쌍을 여러 번 이어도 결과가 달라지지 않는다`() {
        // 질의는 b.id > a.id로 한쪽 방향만 보지만, 그 전제가 깨져도 묶음은 같아야 한다.
        // given
        val unionFind = UnionFind(3)

        // when
        repeat(5) { unionFind.union(0, 1) }
        unionFind.union(1, 0)

        // then
        assertThat(groups(unionFind, 3)).isEqualTo(listOf(listOf(0, 1), listOf(2)))
    }

    @Test
    fun `사슬로 길게 이어도 모두 한 묶음이다`() {
        // 경로 압축과 랭크가 없으면 이 형태에서 트리가 한 줄로 길어진다.
        // given
        val size = 1_000
        val unionFind = UnionFind(size)

        // when
        (0 until size - 1).forEach { unionFind.union(it, it + 1) }

        // then
        assertThat(groups(unionFind, size)).hasSize(1)
        assertThat(unionFind.find(size - 1)).isEqualTo(unionFind.find(0))
    }
}
