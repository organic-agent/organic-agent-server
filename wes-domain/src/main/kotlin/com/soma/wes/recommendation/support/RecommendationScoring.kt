package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.RecommendationCombinedScoreDto
import com.soma.wes.recommendation.domain.PhotoSubject
import com.soma.wes.recommendation.dto.RecommendationTypeStatDto
import kotlin.math.sqrt

/**
 * v3 점수식 — AI repo `recommend/scoring.py`의 자리다.
 *
 *     prior(p) = reco_base                                      (갤러리 사진 전부에 있을 때 — AI repo categorize `service/reco.py`)
 *              = w_t·technical_pct + w_a·aesthetic_pct          (그 밖 — 이전에 분석한 갤러리. 둘 다 갤러리 내 백분위 0~100)
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
    private const val EPS = 1e-12

    fun z(values: DoubleArray): DoubleArray {
        if (values.isEmpty()) return values
        val mean = values.average()
        val std = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        val divisor = if (std > EPS) std else 1.0
        return DoubleArray(values.size) { (values[it] - mean) / divisor }
    }

    /**
     * 사진별 기본 점수. [recoBase]가 사진 전부에 있으면 그 값이고, 한 장이라도 없으면 전부 화질·미학 백분위 식이다.
     *
     * 섞지 않는 이유: `reco_base`는 (거름 → 연사 크기 → 눈 감김·웃음 규격) 순서의 백분위라 화질·미학 식과 뜻이 다르다.
     * 한 갤러리 안에서 두 값을 나란히 놓고 표준화하면 순서가 의미를 잃는다. categorize 는 갤러리 단위로 쓰거나 안 쓰므로
     * 일부만 있는 것은 분석이 진행 중이거나 어긋난 상태다.
     */
    fun prior(technicalPct: DoubleArray, aestheticPct: DoubleArray, recoBase: List<Double?>): DoubleArray {
        val base = recoBase.filterNotNull()
        if (base.isNotEmpty() && base.size == technicalPct.size) return base.toDoubleArray()
        return DoubleArray(technicalPct.size) { W_TECHNICAL * technicalPct[it] + W_AESTHETIC * aestheticPct[it] }
    }

    /** 유형별 집계 — 갤러리 비율, 부족분(deficit), 선택 lift. 전부 [-1, 1] 안의 비율. */
    fun typeStats(types: List<PhotoSubject>, selected: BooleanArray): Map<PhotoSubject, RecommendationTypeStatDto> {
        val n = types.size
        val nSel = selected.count { it }
        return types.toSortedSet().associateWith { type ->
            val idx = types.indices.filter { types[it] == type }
            val pool = if (n > 0) idx.size.toDouble() / n else 0.0
            val selCount = idx.count { selected[it] }
            val sel = if (nSel > 0) selCount.toDouble() / nSel else 0.0
            val pSelGivenT = if (idx.isNotEmpty()) selCount.toDouble() / idx.size else 0.0
            val pSel = if (n > 0) nSel.toDouble() / n else 0.0
            RecommendationTypeStatDto(
                poolShare = pool,
                deficit = maxOf(0.0, pool - sel),
                selectLift = pSelGivenT - pSel,
                poolCount = idx.size,
                selCount = selCount,
            )
        }
    }

    fun combine(priorRaw: DoubleArray, types: List<PhotoSubject>?, stats: Map<PhotoSubject, RecommendationTypeStatDto>?): RecommendationCombinedScoreDto {
        val pz = z(priorRaw)
        val zeros = DoubleArray(pz.size)
        if (types == null || stats == null) return RecommendationCombinedScoreDto(pz, pz, zeros, zeros)
        val deficit = DoubleArray(types.size) { stats[types[it]]?.deficit ?: 0.0 }
        val lift = DoubleArray(types.size) { stats[types[it]]?.selectLift ?: 0.0 }
        val bz = if (std(deficit) > EPS) z(deficit) else zeros
        val az = if (std(lift) > EPS) z(lift) else zeros
        val score = DoubleArray(pz.size) { pz[it] + W_BALANCE * bz[it] + W_PREF * az[it] }
        return RecommendationCombinedScoreDto(score, pz, bz, az)
    }

    /**
     * 신랑·신부 단독 컷의 균형 가산점. 담은 사진 중 신랑 단독·신부 단독 수만 본다(부부·단체·미상은 세지 않는다).
     *
     *     쏠림 = (담은 신랑 − 담은 신부) / (담은 신랑 + 담은 신부)   ∈ [−1, 1]
     *     신부 단독 컷 += W·max(0, 쏠림),  신랑 단독 컷 += W·max(0, −쏠림)
     *
     * 둘 다 하나도 안 담았으면 0이다.
     */
    fun spouseBalance(types: List<PhotoSubject>, selected: Collection<Int>): DoubleArray {
        val bride = selected.count { types[it] == PhotoSubject.BRIDE }
        val groom = selected.count { types[it] == PhotoSubject.GROOM }
        if (bride + groom == 0) return DoubleArray(types.size)
        val tilt = (groom - bride).toDouble() / (bride + groom)
        return DoubleArray(types.size) {
            when (types[it]) {
                PhotoSubject.BRIDE -> W_SPOUSE_BALANCE * maxOf(0.0, tilt)
                PhotoSubject.GROOM -> W_SPOUSE_BALANCE * maxOf(0.0, -tilt)
                PhotoSubject.COUPLE, PhotoSubject.GROUP, PhotoSubject.UNKNOWN -> 0.0
            }
        }
    }

    private fun std(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
}
