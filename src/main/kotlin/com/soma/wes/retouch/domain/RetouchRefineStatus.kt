package com.soma.wes.retouch.domain

/**
 * 정제 결과의 판정. 부부 화면은 이 값으로 정리안·질문·원문 중 무엇을 보여줄지 가른다.
 *
 * 셋 다 API 계약이다 — 클라이언트가 세 갈래를 모두 그려 둬야 되묻기가 들어올 때 화면이 깨지지 않는다.
 * 전체 설계는 `docs/plans/2026-09-12/retouch-refine-v2.md`.
 */
enum class RetouchRefineStatus {

    /** 작가가 바로 작업할 수 있는 정리안을 만들었다. */
    READY,

    /** 해석이 갈려 부부에게 한 번 되묻는다. 되묻기 단계부터 쓰인다. */
    NEEDS_CLARIFICATION,

    /** 정리할 보정 요청이 아니다. 원문을 그대로 둔다. */
    NOT_A_REQUEST,
}
