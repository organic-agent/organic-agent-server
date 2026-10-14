package com.soma.wes.recommendation.support

import kotlin.math.sqrt

/**
 * v3 점수식 — AI repo `recommend/scoring.py`의 자리다.
 *
 *     prior(p) = w_t·technical_pct + w_a·aesthetic_pct          (갤러리 내 백분위)
 *     score(p) = z(prior) [+ w_bal·z(deficit(type p)) + w_pref·z(affinity(type p))]
 *
 * 대괄호는 4단계 — 피사체를 믿고 담은 사진이 충분할 때만 붙는다. 폴더별 추천에서도 점수는 갤러리 내 백분위 그대로다.
 */
object RecommendationScoring {

    const val W_TECHNICAL = 0.5
    const val W_AESTHETIC = 0.5
    const val W_BALANCE = 0.5
    const val W_PREF = 0.5

    /**
     * 신랑·신부 균형 가산점의 상한(z 단위). 담은 사진이 한쪽으로만 쏠리면 적은 쪽 단독 컷에 이만큼까지 더한다.
     * 피사체 판정(`subjects`)이 틀릴 수 있어 순서를 흔드는 정도로만 둔다.
     */
    const val W_SPOUSE_BALANCE = 0.5
    const val BRIDE = "bride"
    const val GROOM = "groom"
    private const val EPS = 1e-12

    data class TypeStat(
        val poolShare: Double,
        val deficit: Double,
        val selectLift: Double,
        val poolCount: Int,
        val selCount: Int,
    )

    data class Combined(
        val score: DoubleArray,
        val priorZ: DoubleArray,
        val balanceZ: DoubleArray,
        val affinityZ: DoubleArray,
    )

    fun z(values: DoubleArray): DoubleArray {
        if (values.isEmpty()) return values
        val mean = values.average()
        val std = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        val divisor = if (std > EPS) std else 1.0
        return DoubleArray(values.size) { (values[it] - mean) / divisor }
    }

    fun prior(technicalPct: DoubleArray, aestheticPct: DoubleArray): DoubleArray =
        DoubleArray(technicalPct.size) { W_TECHNICAL * technicalPct[it] + W_AESTHETIC * aestheticPct[it] }

    /** 유형별 집계 — 갤러리 비율, 부족분(deficit), 선택 lift. 전부 [-1, 1] 안의 비율. */
    fun typeStats(types: List<String>, selected: BooleanArray): Map<String, TypeStat> {
        val n = types.size
        val nSel = selected.count { it }
        return types.toSortedSet().associateWith { type ->
            val idx = types.indices.filter { types[it] == type }
            val pool = if (n > 0) idx.size.toDouble() / n else 0.0
            val selCount = idx.count { selected[it] }
            val sel = if (nSel > 0) selCount.toDouble() / nSel else 0.0
            val pSelGivenT = if (idx.isNotEmpty()) selCount.toDouble() / idx.size else 0.0
            val pSel = if (n > 0) nSel.toDouble() / n else 0.0
            TypeStat(
                poolShare = pool,
                deficit = maxOf(0.0, pool - sel),
                selectLift = pSelGivenT - pSel,
                poolCount = idx.size,
                selCount = selCount,
            )
        }
    }

    fun combine(priorRaw: DoubleArray, types: List<String>?, stats: Map<String, TypeStat>?): Combined {
        val pz = z(priorRaw)
        val zeros = DoubleArray(pz.size)
        if (types == null || stats == null) return Combined(pz, pz, zeros, zeros)
        val deficit = DoubleArray(types.size) { stats[types[it]]?.deficit ?: 0.0 }
        val lift = DoubleArray(types.size) { stats[types[it]]?.selectLift ?: 0.0 }
        val bz = if (std(deficit) > EPS) z(deficit) else zeros
        val az = if (std(lift) > EPS) z(lift) else zeros
        val score = DoubleArray(pz.size) { pz[it] + W_BALANCE * bz[it] + W_PREF * az[it] }
        return Combined(score, pz, bz, az)
    }

    /**
     * 신랑·신부 단독 컷의 균형 가산점. 담은 사진 중 신랑 단독·신부 단독 수만 본다(부부·단체·미상은 세지 않는다).
     *
     *     쏠림 = (담은 신랑 − 담은 신부) / (담은 신랑 + 담은 신부)   ∈ [−1, 1]
     *     신부 단독 컷 += W·max(0, 쏠림),  신랑 단독 컷 += W·max(0, −쏠림)
     *
     * 둘 다 하나도 안 담았으면 0이다.
     */
    fun spouseBalance(types: List<String>, selected: Collection<Int>): DoubleArray {
        val bride = selected.count { types[it] == BRIDE }
        val groom = selected.count { types[it] == GROOM }
        if (bride + groom == 0) return DoubleArray(types.size)
        val tilt = (groom - bride).toDouble() / (bride + groom)
        return DoubleArray(types.size) {
            when (types[it]) {
                BRIDE -> W_SPOUSE_BALANCE * maxOf(0.0, tilt)
                GROOM -> W_SPOUSE_BALANCE * maxOf(0.0, -tilt)
                else -> 0.0
            }
        }
    }

    private fun std(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
}
