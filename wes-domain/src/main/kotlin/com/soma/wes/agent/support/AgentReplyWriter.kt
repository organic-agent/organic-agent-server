package com.soma.wes.agent.support

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.StatsMetric
import com.soma.wes.agent.domain.StatsScope
import com.soma.wes.agent.domain.UnsupportedCondition
import com.soma.wes.agent.dto.response.AgentMessageResponse
import org.springframework.stereotype.Component

/**
 * 답 문장을 템플릿으로 쓴다. LLM에게 다시 쓰게 하지 않는다 — 판단 한 번으로 끝내야 응답 3초 안이고(QA-3),
 * 숫자가 틀릴 일이 없다. 균형 판단도 규칙이다: 가장 많은 칸이 가장 적은 칸의 [IMBALANCE_RATIO]배 이상이거나 0장인 칸이 있으면 치우쳤다.
 */
@Component
class AgentReplyWriter {

    companion object {

        const val IMBALANCE_RATIO = 2
        private const val ROW_SEPARATOR = " · "
    }

    fun folder(candidates: List<AgentMessageResponse.FolderCandidate>): String =
        if (candidates.size == 1) {
            "'${candidates.single().name}' 폴더를 열었어요."
        } else {
            "이 중 어느 폴더일까요? ${candidates.joinToString(ROW_SEPARATOR) { it.name }} — 가장 비슷한 첫 번째를 열었어요."
        }

    fun noFolder(): String = "말씀하신 폴더를 찾지 못했어요. 폴더 이름이나 찍은 순서로 다시 말해 주세요."

    fun filtered(count: Int, unsupported: List<UnsupportedCondition>): String {
        val main = if (count == 0) "조건에 맞는 사진이 없어요." else "조건에 맞는 사진 ${count}장이에요."
        return "$main${unsupportedNote(unsupported)}"
    }

    fun stats(stats: AgentMessageResponse.Stats, unsupported: List<UnsupportedCondition>): String {
        val main = when (stats.metric) {
            StatsMetric.COUNT -> count(stats)
            StatsMetric.DUPLICATES -> duplicates(stats)
            StatsMetric.BY_CONCEPT, StatsMetric.BY_DETAIL, StatsMetric.BY_SUBJECTS, StatsMetric.BY_ORIENTATION -> distribution(stats)
        }
        return "$main${unsupportedNote(unsupported)}"
    }

    private fun count(stats: AgentMessageResponse.Stats): String {
        if (stats.scope == StatsScope.GALLERY) return "조건에 맞는 사진이 갤러리에 ${stats.total}장 있어요."
        val limit = stats.limit ?: return "지금 ${stats.total}장 골랐어요."
        return when {
            stats.total > limit -> "지금 ${stats.total}장 골랐어요. 계약 ${limit}장보다 ${stats.total - limit}장 많아요."
            stats.total == limit -> "지금 ${stats.total}장 골랐어요. 계약 ${limit}장을 꽉 채웠어요."
            else -> "지금 ${stats.total}장 골랐어요. 계약 ${limit}장까지 ${limit - stats.total}장 남았어요."
        }
    }

    private fun duplicates(stats: AgentMessageResponse.Stats): String {
        val (groups, extra) = stats.rows.map { it.count }
        if (groups == 0) return "비슷한 연사 컷을 겹쳐 고른 것은 없어요."
        return "비슷한 연사 컷을 두 장 이상 고른 묶음이 ${groups}개예요. 묶음마다 한 장만 남기면 ${extra}장 줄일 수 있어요."
    }

    private fun distribution(stats: AgentMessageResponse.Stats): String {
        if (stats.total == 0) {
            return if (stats.scope == StatsScope.SELECTED) "아직 고른 사진이 없어요." else "조건에 맞는 사진이 없어요."
        }
        val scope = if (stats.scope == StatsScope.SELECTED) "고른 ${stats.total}장" else "${stats.total}장"
        val listed = stats.rows.joinToString(ROW_SEPARATOR) { "${it.label} ${it.count}장" }
        return "$scope 중 ${listed}이에요.${balanceNote(stats)}"
    }

    /** 폴더·피사체로 나눈 것만 균형을 말한다. 미분류·알 수 없음은 고른 사람이 정한 칸이 아니라 빼고 본다. */
    private fun balanceNote(stats: AgentMessageResponse.Stats): String {
        if (stats.metric == StatsMetric.BY_ORIENTATION) return ""
        val rows = stats.rows.filter { it.label != AgentStatsCounter.UNASSIGNED_LABEL && it.label != AgentStatsCounter.UNKNOWN_LABEL }
        if (rows.size < 2) return ""
        val most = rows.maxBy { it.count }
        val least = rows.minBy { it.count }
        val imbalanced = least.count == 0 || most.count >= least.count * IMBALANCE_RATIO
        return if (imbalanced) " ${most.label}에 많이 몰려 있고 ${least.label}이(가) 적어요." else " 고르게 나뉘어 있어요."
    }

    fun plan(
        op: AgentActionOp,
        action: AgentMessageResponse.Action,
        targetName: String?,
        unsupported: List<UnsupportedCondition>,
    ): String {
        if (action.photoCount == 0) return "바꿀 사진이 없어요. 조건에 맞는 사진이 이미 그 상태예요.${unsupportedNote(unsupported)}"
        val main = when (op) {
            AgentActionOp.SELECT_ADD -> "사진 ${action.photoCount}장을 선택에 넣을게요."
            AgentActionOp.SELECT_REMOVE -> "고른 사진 중 ${action.photoCount}장을 뺄게요."
            AgentActionOp.MOVE -> "사진 ${action.photoCount}장을 '${targetName ?: "미분류"}'(으)로 옮길게요."
        }
        val skipped = if (action.skippedPhotoCount > 0) " 이미 그 상태인 ${action.skippedPhotoCount}장은 뺐어요." else ""
        val overLimit = if (action.overLimitBy > 0) " 넣으면 계약 장수를 ${action.overLimitBy}장 넘어서 실행되지 않아요. 조건을 좁혀 주세요." else ""
        val broad = if (action.broad) " 대부분의 사진이 바뀌는 큰 변경이에요. 정말 이대로 할지 확인해 주세요." else ""
        return "$main$skipped$overLimit$broad${unsupportedNote(unsupported)}"
    }

    fun notEditable(): String = "선택을 이미 제출해서 사진을 넣거나 빼거나 옮길 수 없어요."

    private fun unsupportedNote(unsupported: List<UnsupportedCondition>): String =
        if (unsupported.isEmpty()) "" else " (${unsupported.joinToString(", ") { it.label }}(으)로는 아직 거르지 못해서 그 조건은 빼고 봤어요.)"
}
