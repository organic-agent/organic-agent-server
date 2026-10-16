package com.soma.wes.agent.domain

/**
 * 말에 있지만 지금 데이터로 거를 수 없는 조건. 인물·얼굴·소품·구도 색인이 생기기 전까지는 걸러 주지 않고 못 한다고 말한다.
 * 폴더 이름으로 대신 거르지 않는다 — "베일 쓴 사진"을 "베일" 폴더로 바꾸면 폴더 밖의 베일 사진이 빠진다.
 */
enum class UnsupportedCondition(val label: String) {
    PERSON("특정 인물"),
    FACE("표정·눈 감김"),
    PROP("소품"),
    COMPOSITION("구도"),
}
