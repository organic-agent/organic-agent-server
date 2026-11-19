package com.soma.wes.agent.domain

/**
 * 되묻기 전에 하려던 일(#412 결정 9). 대상(어떤 사진·어느 폴더)이 없어 되물을 때 응답에 싣고, 웹이 history로 돌려보내면 판단기가
 * "블랙수트"·"두 번째 거"처럼 대상만 말한 다음 질문을 그 일로 잇는다. 서버는 대화를 저장하지 않으니 이 칸이 하려던 일의 유일한 기록이다.
 *
 * 이름 바꾸기는 컨셉·세부를 아직 모르는 때라 [AgentActionOp]의 두 칸이 아니라 하나다.
 */
enum class AgentPendingOp {
    SELECT_ADD,
    SELECT_REMOVE,
    MOVE,
    RENAME,
    ;

    /** 도구 인자·판단 문맥의 표기. `select_remove`. */
    val code: String
        get() = name.lowercase()
}
