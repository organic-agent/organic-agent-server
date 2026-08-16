package com.soma.wes.cluster.support

import com.soma.wes.cluster.dto.SimilarPairDto
import com.soma.wes.cluster.dto.SimilarPairDto.TimeRelation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MutualKnnEdgeFilterTest {

    private fun edge(
        left: Long,
        right: Long,
        distance: Double,
        timeRelation: TimeRelation = TimeRelation.OUT_OF_WINDOW,
    ) = SimilarPairDto(leftId = left, rightId = right, distance = distance, timeRelation = timeRelation)

    @Test
    fun `k가 0이면 간선을 그대로 돌려준다`() {
        // 레벨 번들이 필터를 끄는 방법이다. 창 밖의 비상호 간선도 손대지 않아야 한다.
        // given
        val edges = listOf(edge(1, 2, 0.01), edge(2, 3, 0.02))

        // when & then
        assertThat(MutualKnnEdgeFilter.filter(edges = edges, k = 0)).isEqualTo(edges)
    }

    @Test
    fun `시간 창 안 간선은 상호 이웃이 아니어도 살아남는다`() {
        // 창 안 체이닝은 풀샷↔클로즈업을 잇는 recall 동력이라 필터에서 면제된다.
        // 2의 1-이웃은 1뿐이라 2-3은 상호가 아니지만, 창 안이므로 그대로 남는다.
        // given
        val edges = listOf(edge(1, 2, 0.01), edge(2, 3, 0.02, TimeRelation.WITHIN_WINDOW))

        // when & then
        assertThat(MutualKnnEdgeFilter.filter(edges = edges, k = 1)).isEqualTo(edges)
    }

    @Test
    fun `촬영 시각을 잴 수 없는 간선도 살아남는다`() {
        // 필터의 전제는 "시간이 멀면 우연"이다. EXIF가 없으면 멀다고 단정할 근거가 없으므로
        // 자르지 않는다 — EXIF 전무 갤러리의 연사 클리크가 조각나면 안 된다.
        // given
        val edges = listOf(edge(1, 2, 0.01), edge(2, 3, 0.02, TimeRelation.UNKNOWN))

        // when & then
        assertThat(MutualKnnEdgeFilter.filter(edges = edges, k = 1)).isEqualTo(edges)
    }

    @Test
    fun `창 밖 간선은 서로가 서로의 k-이웃일 때만 살아남는다`() {
        // 다리 사진이 잘리는 원리다. 9는 1을 가장 가까운 이웃으로 꼽지만, 1에게는 자기
        // 장면의 2·3이 더 가까워 9를 되받지 않는다 — 상호성이 깨져 다리 간선만 떨어진다.
        // given
        val scene = listOf(edge(1, 2, 0.01), edge(2, 3, 0.01), edge(1, 3, 0.02))
        val bridge = edge(1, 9, 0.05)

        // when
        val survived = MutualKnnEdgeFilter.filter(edges = scene + bridge, k = 2)

        // then
        assertThat(survived).isEqualTo(scene)
    }

    @Test
    fun `거리가 같으면 id가 작은 쪽이 이웃 자리를 차지한다`() {
        // 같은 요청을 두 번 보내면 같은 간선이 살아남아야 프론트가 안정적으로 그린다.
        // given
        val edges = listOf(edge(1, 5, 0.01), edge(2, 5, 0.01))

        // when
        val survived = MutualKnnEdgeFilter.filter(edges = edges, k = 1)

        // then
        assertThat(survived).isEqualTo(listOf(edge(1, 5, 0.01)))
    }
}
