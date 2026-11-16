package com.soma.wes.agent.domain

/**
 * 도우미가 계획할 수 있는 쓰기. 제출·철회·삭제·별점은 일부러 없다 — 되돌리기 어렵거나 부부가 직접 해야 하는 일이다.
 * 이름 바꾸기(#403)는 사진이 아니라 폴더 하나를 바꾼다 — [isRename]이면 사진 id 대신 폴더 id·이름 칸을 쓴다.
 */
enum class AgentActionOp {
    SELECT_ADD,
    SELECT_REMOVE,
    MOVE,
    RENAME_CONCEPT,
    RENAME_DETAIL,
    ;

    val isRename: Boolean
        get() = this == RENAME_CONCEPT || this == RENAME_DETAIL
}
