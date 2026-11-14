package com.soma.wes.agent.support

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentActionStep
import com.soma.wes.agent.domain.PhotoFilter
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
 *
 * 큰 변경(`broad`)도 여기서 판정한다 — 조건 없이 전부를 바꾸거나, 바꿀 수 있는 사진의 [BROAD_SHARE_PERCENT]% 이상을 바꾸는 계획.
 * 질문 세트에서 잘못 실행된 계획 2건이 모두 "다 빼줘"였다. 막지는 않고 확인 화면이 한 번 더 경고하게 한다 — 정말 전부 빼려는 사람도 있다.
 */
@Component
class AgentActionPlanner {

    companion object {

        const val BROAD_SHARE_PERCENT = 80

        /** 몇 장 안 되는 갤러리에서 "3장 중 3장"을 큰 변경이라 부르지 않는다. */
        const val BROAD_MIN_PHOTOS = 10
        private const val PERCENT = 100
    }

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
            broad = isBroad(snapshot, decision, targets.size),
        )
    }

    /** 바꿀 수 있는 사진 = 빼기는 고른 사진, 넣기는 안 고른 사진, 옮기기는 갤러리 전체. 거기서 얼마나 바꾸는가로 본다. */
    private fun isBroad(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.PlanAction, changing: Int): Boolean {
        if (changing < BROAD_MIN_PHOTOS) return false
        if (!decision.filter.narrows()) return true
        val changeable = when (decision.op) {
            AgentActionOp.SELECT_REMOVE -> snapshot.photos.count { it.selected }
            AgentActionOp.SELECT_ADD -> snapshot.photos.count { !it.selected }
            AgentActionOp.MOVE -> snapshot.photos.size
        }
        return changing * PERCENT >= changeable * BROAD_SHARE_PERCENT
    }

    /** 선택 여부만 건 조건은 좁히지 않는다 — "고른 거 다 빼줘"는 빼기의 대상 그 자체다. */
    private fun PhotoFilter.narrows(): Boolean =
        hasFolder() || subjects.isNotEmpty() || orientation != null || burstRepresentativeOnly

    private fun overLimitBy(snapshot: AgentGallerySnapshotDto, op: AgentActionOp, adding: Int): Int {
        val limit = snapshot.maxSelectablePhotoCount ?: return 0
        if (op != AgentActionOp.SELECT_ADD) return 0
        val selectedCount = snapshot.photos.count { it.selected }
        return (selectedCount + adding - limit).coerceAtLeast(0)
    }
}
