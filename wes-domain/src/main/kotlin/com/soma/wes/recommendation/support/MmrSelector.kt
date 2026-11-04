package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.MmrPickDto

/**
 * 폴더 안 선택 — 연사당 1장 남기고 MMR(λ·score − (1−λ)·maxCos)로 n장. AI repo `rerank.select_in_folder`의 자리다.
 * 임베딩은 정규화돼 있다고 본다(내적 = 코사인).
 */
object MmrSelector {

    const val LAMBDA = 0.7

    fun mmrSelect(score: DoubleArray, emb: Array<FloatArray>, k: Int, lambda: Double, candidates: List<Int>): List<Int> {
        if (candidates.isEmpty() || k <= 0) return emptyList()
        val selected = mutableListOf<Int>()
        val maxSim = DoubleArray(candidates.size) { Double.NEGATIVE_INFINITY }
        val alive = BooleanArray(candidates.size) { true }
        repeat(minOf(k, candidates.size)) {
            var best = -1
            var bestValue = Double.NEGATIVE_INFINITY
            for (i in candidates.indices) {
                if (!alive[i]) continue
                val penalty = if (maxSim[i].isFinite()) maxSim[i] else 0.0
                val mmr = lambda * score[candidates[i]] - (1 - lambda) * penalty
                if (mmr > bestValue) {
                    bestValue = mmr
                    best = i
                }
            }
            if (best < 0) return selected
            val picked = candidates[best]
            selected += picked
            alive[best] = false
            for (i in candidates.indices) {
                maxSim[i] = maxOf(maxSim[i], dot(emb[candidates[i]], emb[picked]))
            }
        }
        return selected
    }

    /** 폴더 안 선택. 반환은 폴더 안 점수 순위 순이다. */
    fun selectInFolder(
        score: DoubleArray,
        emb: Array<FloatArray>,
        members: List<Int>,
        burstIds: IntArray,
        n: Int,
        lambda: Double = LAMBDA,
    ): List<MmrPickDto> {
        if (members.isEmpty() || n <= 0) return emptyList()
        val bestPerBurst = linkedMapOf<Int, Int>()
        members.forEach { i ->
            val burst = burstIds[i]
            val current = bestPerBurst[burst]
            if (current == null || score[i] > score[current]) bestPerBurst[burst] = i
        }
        val candidates = bestPerBurst.values.toList()
        val picked = mmrSelect(score, emb, n, lambda, candidates)
        val byScore = candidates.sortedByDescending { score[it] }.withIndex().associate { (rank, i) -> i to rank + 1 }
        return picked.map { MmrPickDto(index = it, folderRank = byScore.getValue(it), quota = n) }.sortedBy { it.folderRank }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
