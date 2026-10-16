package com.soma.wes.agent.domain

/**
 * 사진의 피사체. `photo_analysis.subjects`(score 워커의 CLIP zero-shot)를 도우미의 말로 옮긴 것이다.
 *
 * `unknown`은 옮기지 않는다 — "누가 나왔는지 모름"은 거를 조건이 아니라 걸러지지 않는 사진이다.
 */
enum class AgentSubject(val analysisValue: String, val label: String) {
    BRIDE("bride", "신부 단독"),
    GROOM("groom", "신랑 단독"),
    COUPLE("couple", "두 분 함께"),
    GROUP("group", "단체"),
    ;

    companion object {

        fun fromAnalysis(value: String?): AgentSubject? = entries.firstOrNull { it.analysisValue == value }
    }
}
