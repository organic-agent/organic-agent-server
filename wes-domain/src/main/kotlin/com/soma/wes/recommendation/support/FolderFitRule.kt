package com.soma.wes.recommendation.support

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 폴더와 동떨어진 사진 판정 — 폴더 안 다른 사진들의 중심(leave-one-out centroid)과의 코사인 유사도가 폴더 분포에서
 * 뚜렷이 낮은 사진을 고른다. 사용자가 실수로 다른 폴더에 옮긴 사진이 그 폴더의 추천에 오르지 않게 하는 게이트다 —
 * 추천 후보에서만 빼고, 배정은 건드리지 않는다(어디에 둘지는 사용자 몫).
 *
 * 규칙은 강건 통계다: 중앙값 m, MAD → σ 환산. 사진 i는 `m − sim_i ≥ MIN_GAP` 이고 `m − sim_i > ROBUST_Z·σ` 일 때 이상치.
 * 폴더가 작으면(MIN_FOLDER_SIZE 미만) 중심이 의미가 없어 판정하지 않고, 이상치가 너무 많으면(MAX_MISFIT_RATIO 초과)
 * 잘못 들어온 사진이 아니라 폴더 자체가 여러 장면의 묶음이라 보고 하나도 빼지 않는다.
 * 임베딩은 정규화돼 있다고 본다(내적 = 코사인). 상수는 초기값이며 실데이터로 보정한다.
 */
object FolderFitRule {

    /** 이 크기 미만 폴더는 판정하지 않는다 — 표본이 적으면 중심이 곧 한두 장이다. */
    const val MIN_FOLDER_SIZE = 6

    /** 강건 z(MAD 기준) 하한. 3 ≈ 정규분포의 3σ. */
    const val ROBUST_Z = 3.0

    /** 중앙값과의 최소 코사인 격차 — 아주 균일한 폴더(σ≈0)에서 작은 흔들림이 이상치로 잡히지 않게 하는 절대 하한. */
    const val MIN_GAP = 0.10

    /** 이 비율보다 많이 걸리면 폴더가 섞인 것이지 사진이 잘못 든 게 아니다 — 아무것도 빼지 않는다. */
    const val MAX_MISFIT_RATIO = 0.2

    /** MAD → σ 환산 계수(정규분포). */
    private const val MAD_TO_SIGMA = 1.4826

    private const val EPS = 1e-9

    /** [members]는 정렬된 분석 행의 인덱스(폴더의 사진 전부). 반환은 그중 이상치 인덱스. */
    fun misfits(emb: Array<FloatArray>, members: List<Int>): Set<Int> {
        if (members.size < MIN_FOLDER_SIZE) return emptySet()
        val dim = emb[members.first()].size
        val sum = DoubleArray(dim)
        members.forEach { i -> val e = emb[i]; for (d in 0 until dim) sum[d] += e[d] }

        val sims = DoubleArray(members.size) { k ->
            val e = emb[members[k]]
            var dot = 0.0
            var norm = 0.0
            for (d in 0 until dim) {
                val c = sum[d] - e[d]
                dot += e[d] * c
                norm += c * c
            }
            if (norm < EPS) 0.0 else dot / sqrt(norm)
        }

        val median = median(sims)
        val mad = median(DoubleArray(sims.size) { abs(sims[it] - median) })
        val sigma = mad * MAD_TO_SIGMA
        val misfit = members.indices.filter { k ->
            val gap = median - sims[k]
            gap >= MIN_GAP && gap > ROBUST_Z * sigma
        }
        if (misfit.size > ceil(members.size * MAX_MISFIT_RATIO).toInt()) return emptySet()
        return misfit.mapTo(mutableSetOf()) { members[it] }
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }
}
