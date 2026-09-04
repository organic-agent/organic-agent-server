package com.soma.wes.recommendation.support

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 폴더별 목표 장수 n_f — AI repo `rerank.folder_quota`의 자리다.
 *
 *     n_f = max(1, round(target·|f|/Σ|f'|)),  단 n_f ≤ max(1, ceil(|f|·cap_ratio))
 *
 * target ≤ 0(담은 사진이 목표에 닿은 뒤의 refine)이어도 폴더마다 1장은 남긴다 — 폴더 화면에서 AI 마크가 사라지는 것이 더 이상하다.
 * 키 null은 미분류 가상 폴더다.
 */
object FolderQuota {

    const val CAP_RATIO = 0.5

    fun quota(sizes: Map<Long?, Int>, target: Int, capRatio: Double = CAP_RATIO): Map<Long?, Int> {
        val total = sizes.values.sum()
        if (total == 0) return sizes.mapValues { 0 }
        val effective = maxOf(target, 0)
        return sizes.mapValues { (_, n) ->
            val proportional = maxOf(1, (effective.toDouble() * n / total).roundToInt())
            minOf(proportional, maxOf(1, ceil(n * capRatio).toInt()))
        }
    }
}
