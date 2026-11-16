package com.soma.wes.agent.domain

/** 사용법 답에 붙는 "그 화면으로" 버튼의 목적지. 웹이 경로로 옮긴다 — 서버는 URL을 모른다. */
enum class AgentScreen(val label: String) {
    SELECTION("셀렉 화면"),
    RETOUCH("보정 화면"),
    COLLAB("공유폴더"),
    RECOMMEND("AI 추천"),
}
