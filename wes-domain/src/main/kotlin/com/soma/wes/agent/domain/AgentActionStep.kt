package com.soma.wes.agent.domain

/**
 * 실행 창구로 보내는 동작 하나. 확인 화면의 계획과 되돌리기 쪽지가 같은 모양이라, 되돌리기는 이 동작을 다시 보내는 것이다.
 *
 * [targetDetailFolderId]는 [AgentActionOp.MOVE]에만 쓴다. null이면 미분류로 옮긴다(`MoveFolderPhotosRequest`와 같다).
 */
data class AgentActionStep(
    val op: AgentActionOp,
    val photoIds: List<Long>,
    val targetDetailFolderId: Long? = null,
)
