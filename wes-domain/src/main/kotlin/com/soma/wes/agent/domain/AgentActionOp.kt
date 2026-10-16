package com.soma.wes.agent.domain

/**
 * 도우미가 계획할 수 있는 쓰기. 제출·철회·삭제·별점은 일부러 없다 — 되돌리기 어렵거나 부부가 직접 해야 하는 일이다.
 */
enum class AgentActionOp {
    SELECT_ADD,
    SELECT_REMOVE,
    MOVE,
}
