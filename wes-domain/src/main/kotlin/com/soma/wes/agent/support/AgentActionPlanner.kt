package com.soma.wes.agent.support

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentActionStep
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.dto.response.AgentMessageResponse
import com.soma.wes.agent.repository.projection.AgentPhotoRow
import org.springframework.stereotype.Component

/**
 * 조건에 걸린 사진을 실행 계획으로 바꾼다. 이미 그 상태인 사진은 뺀다 — 선택 담기는 이미 담긴 사진이 하나라도
 * 섞이면 통째로 거절되고(`requireNotSelected`), 사용자는 "이미 고른 6장은 뺐어요"를 확인 화면에서 본다.
 *
 * 계약 장수를 넘는지도 여기서 미리 센다. 넘는 계획도 보여 주되 실행은 기존 규칙(`requireWithinMax`)이 거절한다.
 */
@Component
class AgentActionPlanner {

    fun plan(
        snapshot: AgentGallerySnapshotDto,
        decision: AgentDecisionDto.PlanAction,
        matched: List<AgentPhotoRow>,
    ): AgentMessageResponse.Action {
        val targets = when (decision.op) {
            AgentActionOp.SELECT_ADD -> matched.filter { !it.selected }
            AgentActionOp.SELECT_REMOVE -> matched.filter { it.selected }
            AgentActionOp.MOVE -> matched.filter { it.detailFolderId != decision.targetDetailFolderId }
        }
        val steps = if (targets.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AgentActionStep(
                    op = decision.op,
                    photoIds = targets.map { it.photoId },
                    targetDetailFolderId = decision.targetDetailFolderId.takeIf { decision.op == AgentActionOp.MOVE },
                ),
            )
        }

        return AgentMessageResponse.Action(
            steps = steps,
            photoCount = targets.size,
            skippedPhotoCount = matched.size - targets.size,
            overLimitBy = overLimitBy(snapshot, decision.op, targets.size),
        )
    }

    private fun overLimitBy(snapshot: AgentGallerySnapshotDto, op: AgentActionOp, adding: Int): Int {
        val limit = snapshot.maxSelectablePhotoCount ?: return 0
        if (op != AgentActionOp.SELECT_ADD) return 0
        val selectedCount = snapshot.photos.count { it.selected }
        return (selectedCount + adding - limit).coerceAtLeast(0)
    }
}
