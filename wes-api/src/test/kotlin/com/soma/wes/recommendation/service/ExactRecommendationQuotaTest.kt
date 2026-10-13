package com.soma.wes.recommendation.service

import com.soma.wes.recommendation.support.ExactRecommendationQuota
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExactRecommendationQuotaTest {
    @Test
    fun `명시한 장수를 초과하지 않고 후보가 충분하면 정확히 채운다`() {
        val capacities = linkedMapOf<Long?, Int>(1L to 3, 2L to 2, null to 1, 3L to 0)
        (1..10).forEach { requested ->
            val quota = ExactRecommendationQuota.allocate(capacities, requested)
            assertThat(quota.values.sum()).isEqualTo(minOf(requested, 6))
            capacities.forEach { (id, capacity) -> assertThat(quota.getValue(id)).isBetween(0, capacity) }
        }
    }

    @Test
    fun `기존 50퍼센트 제한 없이 한 폴더에서 지정 장수를 채운다`() {
        assertThat(ExactRecommendationQuota.allocate(mapOf(1L to 12), 10)).containsEntry(1L, 10)
    }
}
