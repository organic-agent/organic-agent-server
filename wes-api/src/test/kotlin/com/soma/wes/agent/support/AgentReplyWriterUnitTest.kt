package com.soma.wes.agent.support

import com.soma.wes.agent.domain.StatsMetric
import com.soma.wes.agent.domain.StatsScope
import com.soma.wes.agent.dto.response.AgentMessageResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentReplyWriterUnitTest {

    private val writer = AgentReplyWriter()

    private fun stats(metric: StatsMetric, scope: StatsScope, total: Int, rows: List<Int> = emptyList()) =
        AgentMessageResponse.Stats(
            metric = metric,
            scope = scope,
            rows = rows.mapIndexed { index, count -> AgentMessageResponse.Stats.Row(label = "칸$index", count = count) },
            total = total,
            limit = 10,
        )

    @Test
    fun `조건 없이 센 고른 사진은 계약까지 남은 장수를 말한다`() {
        // when
        val reply = writer.stats(stats(StatsMetric.COUNT, StatsScope.SELECTED, total = 2), emptyList())

        // then
        assertThat(reply).isEqualTo("지금 2장 골랐어요. 계약 10장까지 8장 남았어요.")
    }

    @Test
    fun `조건을 걸어 센 고른 사진은 전체 선택량이나 남은 장수처럼 말하지 않는다`() {
        // when
        val counted = writer.stats(stats(StatsMetric.COUNT, StatsScope.SELECTED, total = 2), emptyList(), filtered = true)
        val empty = writer.stats(stats(StatsMetric.BY_CONCEPT, StatsScope.SELECTED, total = 0), emptyList(), filtered = true)

        // then
        assertThat(counted).isEqualTo("고른 사진 중 조건에 맞는 사진은 2장이에요.")
        assertThat(empty).isEqualTo("고른 사진 중 조건에 맞는 사진이 없어요.")
    }

    @Test
    fun `갤러리 범위의 연사 묶음은 고른 사진을 줄이라고 말하지 않는다`() {
        // when
        val reply = writer.stats(stats(StatsMetric.DUPLICATES, StatsScope.GALLERY, total = 6, rows = listOf(2, 4)), emptyList())

        // then
        assertThat(reply).isEqualTo("갤러리에 비슷한 연사 컷이 두 장 이상인 묶음이 2개예요. 묶음마다 대표 한 장만 보면 4장 덜 봐도 돼요.")
    }
}
