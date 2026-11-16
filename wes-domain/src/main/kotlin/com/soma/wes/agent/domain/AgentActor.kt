package com.soma.wes.agent.domain

/**
 * 반응·담기를 누가 했나. user id가 아니라 묻는 사람 기준의 이름표다(#403 5-3) — 판단기·웹이 상대 id를 몰라도 되고,
 * 요청·history에 다른 갤러리 사람의 id가 끼어들 틈이 없다. 서버 코드가 요청마다 지금 참여자로 푼다.
 */
enum class AgentActor {
    /** 지금 묻는 사람. */
    ME,

    /** 나를 뺀 부부 참여자. 스튜디오 갤러리에 셋 이상이면 나 아닌 전원이다. */
    PARTNER,
}
