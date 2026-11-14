package com.soma.wes.agent.domain

/**
 * 사용법·상태 질문의 닫힌 주제(#348 ⑤, 3단계 B-1). 판단기는 주제만 고르고 문장은 [com.soma.wes.agent.support.AgentExplainWriter]가
 * 지금 갤러리 상태로 채운다 — 지어내는 답 0(QA-8). 주제 밖은 [UNKNOWN]("모른다").
 *
 * 목록은 부부 권한 기준의 제품 사실에서 왔다(예: 사진 삭제·제출 되돌리기는 작가만). 주제를 늘릴 때는 코드로 사실을 확인하고 템플릿을 같이 쓴다.
 */
enum class ExplainTopic(val label: String) {
    SUBMIT("제출"),
    WITHDRAW("제출 되돌리기"),
    DEADLINE("선택 마감"),
    MAX_PHOTOS("계약 장수·남은 장수"),
    RETOUCH_ROUNDS("보정 회차"),
    SHARE_FAMILY("가족 공유"),
    RATING("별점"),
    MEMO("메모"),
    COMMENT("댓글"),
    DELETE_PHOTO("사진 삭제"),
    DOWNLOAD("내려받기"),
    AI_RECOMMEND("AI 추천"),
    UNKNOWN("모름"),
    ;

    /** 도구 인자 코드. `retouch_rounds`. */
    val code: String
        get() = name.lowercase()

    companion object {

        fun fromCode(value: String?): ExplainTopic? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
