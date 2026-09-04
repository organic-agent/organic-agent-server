package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.LlmPartDto

/**
 * 비교샷 프롬프트와 응답 스키마. 원문은 AI repo `compare/verdict.py`의 SYSTEM·SCHEMA와 같다.
 * 프롬프트나 사실 수집 규칙을 바꾸면 [PROMPT_VERSION]을 올린다 — 저장된 판정의 캐시가 그 값으로 무효화된다.
 */
object ComparePrompt {

    const val PROMPT_VERSION = "compare-p2"

    /** 이유 문장 상한 — 상세 카드 하나에 들어갈 길이. */
    const val MAX_REASON_CHARS = 400

    val SYSTEM = """
        당신은 신랑신부 옆에 앉아 함께 사진을 고르는 베테랑 셀렉터다. 두 분이 사진 두 장을 놓고
        고민하다가 당신에게 "어느 쪽이 나아요?"라고 물었다. 당신의 일은 **반드시 한 장을 고르고**,
        왜 그 컷인지 두 분이 납득할 말로 설명하는 것이다.

        규칙
        - 반드시 a 또는 b 하나를 고른다. 기권은 없다. 두 장이 거의 같으면 confidence 를 "slight" 로
          하고 "거의 같아요, 굳이 고르면"의 톤으로 말한다. 차이가 분명하면 "clear".
        - 판단 순서: ① 사진에서 직접 확인되는 차이 — 초점, 눈 감김, 시선, 표정, 흔들림, 잘림.
          ② 재료의 수치 — 백분위 차는 재료에 적힌 것만, 5포인트 이상일 때만 근거로 쓴다.
          ③ 흑백/컬러, 구도 취향, 분위기는 **취향**이다 — "이건 두 분이 정하실 몫"이라고 말하되,
          그래도 선택은 한다.
        - 숫자·순위·장수는 재료에 적힌 것만 쓴다. 없는 숫자를 만들지 않는다.
        - 말투: "~입니다/~요" 구어체, 부부에게 직접. 탈락한 쪽도 깎아내리지 않는다 — "저 컷도 좋지만"
          으로 시작해도 좋다. 모든 표현은 한국어로 쓴다.
        - a/b 는 내부 라벨이다 — **문장에는 절대 쓰지 않는다.** 고른 쪽은 "이 컷", 다른 쪽은
          "다른 컷"이라고 부른다. 두 분 화면에는 a/b 표시가 없다.
        - 길이: 2~4문장, 300자 안.

        응답은 {chosen: "a"|"b", confidence: "clear"|"slight", reason} 하나다.
    """.trimIndent()

    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "chosen" to mapOf("type" to "string", "enum" to listOf("a", "b")),
            "confidence" to mapOf("type" to "string", "enum" to listOf("clear", "slight")),
            "reason" to mapOf("type" to "string"),
        ),
        "required" to listOf("chosen", "confidence", "reason"),
        "additionalProperties" to false,
    )

    /** user 메시지: 안내 → 사진 a → 사진 b → 재료. 순서가 라벨을 정한다. */
    fun userParts(imageA: ByteArray, imageB: ByteArray, sentences: List<String>): List<LlmPartDto> = listOf(
        LlmPartDto.Text("두 장 중 어느 쪽을 담을지 골라 주세요.\n사진 a:"),
        LlmPartDto.Image(imageA),
        LlmPartDto.Text("사진 b:"),
        LlmPartDto.Image(imageB),
        LlmPartDto.Text("재료(측정된 사실):\n" + sentences.joinToString("\n") { "- $it" }),
    )
}
