package com.soma.wes.recommendation.service

import com.soma.wes.recommendation.dto.PairVerdictDto

/**
 * 비교샷 판정 실행기 — 이 서버의 첫 동기 AI 워크로드다.
 *
 * 잡 큐([AiSelectionJob])를 거치지 않는다: 사용자가 화면에서 기다리는 5초 안팎의 호출이라
 * 요청-응답으로 부르고, 타임아웃·템플릿 폴백은 AI 쪽이 안에서 처리해 항상 판정을 돌려준다.
 * 판정 행(`ai_pair_verdicts`) 저장과 순서 무관 캐시도 AI 쪽 일이다 — 이 서버는 응답만 본다.
 */
interface PairCompareInvoker {

    /** 실행기가 설정돼 있는지. 기동이 아니라 호출 시점에 확인한다 — 테스트에는 없는 것이 정상이다. */
    val isAvailable: Boolean

    /** 두 사진을 판정한다. 캐시 히트면 즉시, 아니면 LLM 판정(예산 8초)을 기다려 돌아온다. */
    fun compare(selectionId: Long, photoA: Long, photoB: Long): PairVerdictDto
}
