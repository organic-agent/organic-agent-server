package com.soma.wes.agent.domain

/**
 * 실행 창구로 보내는 동작 하나. 확인 화면의 계획과 되돌리기 쪽지가 같은 모양이라, 되돌리기는 이 동작을 다시 보내는 것이다.
 *
 * 사진 동작(넣기·빼기·옮기기)은 [photoIds]를 쓰고, [targetDetailFolderId]는 [AgentActionOp.MOVE]에만 쓴다 —
 * null이면 미분류로 옮긴다(`MoveFolderPhotosRequest`와 같다).
 * 이름 바꾸기는 [conceptFolderId](세부면 [detailFolderId]까지)·[name]·[expectedName]을 쓰고 [photoIds]는 비운다.
 * 실행 순간의 이름이 [expectedName]과 다르면 그 동작은 건너뛴다 — 그사이 배우자가 바꾼 이름을 덮지 않기 위해서다(#403 5-2, QA-11).
 */
data class AgentActionStep(
    val op: AgentActionOp,
    val photoIds: List<Long> = emptyList(),
    val targetDetailFolderId: Long? = null,
    val conceptFolderId: Long? = null,
    val detailFolderId: Long? = null,
    val name: String? = null,
    val expectedName: String? = null,
)
