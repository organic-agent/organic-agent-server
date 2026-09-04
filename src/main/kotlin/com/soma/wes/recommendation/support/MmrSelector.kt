package com.soma.wes.recommendation.support

/**
 * 폴더 안 선택 — 연사 클러스터당 1장 남기고 MMR(λ·score − (1−λ)·maxCos)로 n장. AI repo `rerank.select_in_folder`의 자리다.
 * 임베딩은 정규화돼 있다고 본다(내적 = 코사인).
 */
object MmrSelector {

    const val LAMBDA = 0.7

    data class Pick(
        /** 정렬된 분석 행의 인덱스 */
        val index: Int,
        /** 폴더 안 점수 순위(연사 dedup 후), 1부터 */
        val folderRank: Int,
        /** 그 폴더의 n_f */
        val quota: Int,
    )

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
        clusterIds: IntArray,
        n: Int,
        lambda: Double = LAMBDA,
    ): List<Pick> {
        if (members.isEmpty() || n <= 0) return emptyList()
        val bestPerCluster = linkedMapOf<Int, Int>()
        members.forEach { i ->
            val cluster = clusterIds[i]
            val current = bestPerCluster[cluster]
            if (current == null || score[i] > score[current]) bestPerCluster[cluster] = i
        }
        val candidates = bestPerCluster.values.toList()
        val picked = mmrSelect(score, emb, n, lambda, candidates)
        val byScore = candidates.sortedByDescending { score[it] }.withIndex().associate { (rank, i) -> i to rank + 1 }
        return picked.map { Pick(index = it, folderRank = byScore.getValue(it), quota = n) }.sortedBy { it.folderRank }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
