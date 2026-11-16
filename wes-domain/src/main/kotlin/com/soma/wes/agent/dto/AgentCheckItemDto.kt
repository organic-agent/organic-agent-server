package com.soma.wes.agent.dto

import com.soma.wes.agent.domain.AgentCheckCode

/**
 * 점검 항목 하나의 센 결과. 문장은 [com.soma.wes.agent.support.AgentReplyWriter]가 이 값으로 쓴다.
 * [photoIds]·[conceptFolderIds]는 "그 사진 보기"의 대상이고, 나머지는 문장에 넣을 보조 숫자다(항목에 따라 쓰는 칸만 찬다).
 */
data class AgentCheckItemDto(
    val code: AgentCheckCode,
    val count: Int,
    val photoIds: List<Long> = emptyList(),
    val conceptFolderIds: List<Long> = emptyList(),
    val conceptNames: List<String> = emptyList(),
    /** OVER_LIMIT — 계약 장수. */
    val limit: Int? = null,
    /** BURST_DUPLICATE — 두 장 이상 고른 연사 묶음 수. */
    val burstGroups: Int = 0,
    /** SUBJECT_IMBALANCE — 고른 신부 단독·신랑 단독 장수. */
    val brideCount: Int = 0,
    val groomCount: Int = 0,
)
