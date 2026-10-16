package com.soma.wes.agent.dto

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.domain.StatsMetric
import com.soma.wes.agent.domain.StatsScope
import com.soma.wes.agent.domain.UnsupportedCondition

/**
 * 판단기가 쓰는 주문서. 질문 하나에 하나다 — 판단기는 무엇을 할지만 정하고, 사진을 세거나 옮기는 일은 서비스가 한다.
 *
 * 판단기를 Bedrock에서 다른 엔진(규칙·Jev)으로 바꿔도 이 모양은 그대로다. 질문 세트 채점도 이 주문서를 정답과 맞춰 본다.
 */
sealed interface AgentDecisionDto {

    /** 열 폴더 후보, 가장 맞는 것부터. */
    data class FindFolder(
        val targets: List<FolderTarget>,
    ) : AgentDecisionDto

    data class FilterPhotos(
        val filter: PhotoFilter,
        val unsupported: List<UnsupportedCondition> = emptyList(),
    ) : AgentDecisionDto

    data class Stats(
        val metric: StatsMetric,
        val scope: StatsScope,
        val filter: PhotoFilter = PhotoFilter(),
        val unsupported: List<UnsupportedCondition> = emptyList(),
    ) : AgentDecisionDto

    /** [targetDetailFolderId]는 [AgentActionOp.MOVE]에만 쓴다. null이면 미분류로 옮긴다. */
    data class PlanAction(
        val op: AgentActionOp,
        val filter: PhotoFilter,
        val targetDetailFolderId: Long? = null,
        val unsupported: List<UnsupportedCondition> = emptyList(),
    ) : AgentDecisionDto

    data class Clarify(
        val question: String,
    ) : AgentDecisionDto

    /** 세부 폴더면 [detailFolderId]까지, 컨셉 폴더면 [conceptFolderId]만. */
    data class FolderTarget(
        val conceptFolderId: Long,
        val detailFolderId: Long? = null,
    )
}
