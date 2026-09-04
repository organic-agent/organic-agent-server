package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.ComparablePhotoDto
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * LLM 없이 사실만으로 고르는 결정적 판정 — 초점 ▸ 화질 ▸ 미학 ▸ a. AI repo `compare.verdict.template_verdict`의 자리다.
 * LLM이 예산 안에 답하지 못했을 때 "AI가 못 골랐어요"가 아니라 "기준으로만 골랐어요"로 응답하기 위한 것이다.
 */
object TemplateVerdictRule {

    enum class Side { A, B }

    data class Decision(val chosen: Side, val reason: String)

    fun decide(a: ComparablePhotoDto, b: ComparablePhotoDto): Decision {
        val sa = a.sharpness ?: 0.0
        val sb = b.sharpness ?: 0.0
        if (sa > 0 && sb > 0 && max(sa, sb) / max(min(sa, sb), 1e-9) >= PairFactCollector.SHARPNESS_RATIO) {
            return Decision(if (sa >= sb) Side.A else Side.B, "초점이 더 또렷한 쪽을 골랐어요. 눈으로도 한번 비교해 보세요")
        }
        if (abs(a.technicalPct - b.technicalPct) >= PairFactCollector.PCT_GAP) {
            return Decision(if (a.technicalPct >= b.technicalPct) Side.A else Side.B, "화질 점수가 더 높은 쪽을 골랐어요")
        }
        if (abs(a.aestheticPct - b.aestheticPct) >= PairFactCollector.PCT_GAP) {
            return Decision(if (a.aestheticPct >= b.aestheticPct) Side.A else Side.B, "전체 인상 점수가 더 높은 쪽을 골랐어요")
        }
        return Decision(Side.A, "두 장의 측정치가 거의 같아요 — 기준상 앞의 사진을 골랐지만 두 분 취향이 정답이에요")
    }
}
