package com.soma.wes.recommendation.service

import com.soma.wes.recommendation.dto.LlmJsonRequestDto
import tools.jackson.databind.JsonNode

/**
 * 구조화 출력(JSON 스키마 강제) 한 종류만 쓰는 LLM 포트. 텍스트 + (선택) 이미지 블록.
 *
 * 구현은 Bedrock 어댑터 하나와, 설정이 꺼져 있을 때의 비활성 구현이다. 호출자는 [isEnabled]를 먼저
 * 보고 꺼져 있으면 LLM 없는 경로(템플릿 판정·폴백 문장)로 간다 — 부르면 실패한다.
 */
interface StructuredLlmClient {

    val isEnabled: Boolean

    /** 스키마대로 파싱된 JSON. 거부·잘림·계약 밖 응답은 `RecommendationException(LLM_CALL_FAILED)`다. */
    fun completeJson(request: LlmJsonRequestDto): JsonNode
}
