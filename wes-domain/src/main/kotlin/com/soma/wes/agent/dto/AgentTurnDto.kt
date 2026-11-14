package com.soma.wes.agent.dto

import com.soma.wes.agent.domain.AgentReplyKind
import com.soma.wes.agent.domain.AgentTurnRole
import com.soma.wes.agent.domain.PhotoFilter

/**
 * 판단기에게 문맥으로만 주는 이전 대화 한 턴(#403 5-1). 웹이 돌려보낸 값이라 그대로 믿지 않는다 — 폴더 id는 열거나 실행할 때
 * 이 갤러리 것인지 다시 확인하고, 사진은 지금 상태로 다시 센다.
 */
data class AgentTurnDto(
    val role: AgentTurnRole,
    val text: String,
    val kind: AgentReplyKind? = null,
    val folderCandidates: List<AgentDecisionDto.FolderTarget> = emptyList(),
    val filter: PhotoFilter? = null,
)
