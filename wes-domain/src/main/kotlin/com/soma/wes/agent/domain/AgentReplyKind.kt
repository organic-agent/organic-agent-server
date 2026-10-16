package com.soma.wes.agent.domain

/** 질문 하나에 돌려주는 답의 종류. 화면이 무엇을 할지(폴더 열기·필터·답만·확인 버튼)가 여기서 갈린다. */
enum class AgentReplyKind {
    FIND_FOLDER,
    FILTER_PHOTOS,
    STATS,
    PLAN_ACTION,
    CLARIFY,
}
