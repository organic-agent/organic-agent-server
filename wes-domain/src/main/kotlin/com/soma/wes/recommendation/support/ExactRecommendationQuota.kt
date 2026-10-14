package com.soma.wes.recommendation.support

/** 명시한 장수를 폴더의 실제 후보 수에 비례 배분한다. 반올림 때문에 장수가 늘거나 빈 폴더에 배정되지 않는다. */
object ExactRecommendationQuota {
    fun allocate(capacities: Map<Long?, Int>, requested: Int): Map<Long?, Int> {
        val total = capacities.values.sumOf { it.toLong() }
        if (total == 0L) return capacities.mapValues { 0 }
        val target = minOf(requested.toLong(), total)
        val result = capacities.mapValues { (_, capacity) -> (target * capacity / total).toInt() }.toMutableMap()
        var remaining = target.toInt() - result.values.sum()
        capacities.entries.sortedByDescending { target * it.value % total }.forEach { (id, capacity) ->
            if (remaining > 0 && result.getValue(id) < capacity) {
                result[id] = result.getValue(id) + 1
                remaining--
            }
        }
        return result
    }
}
