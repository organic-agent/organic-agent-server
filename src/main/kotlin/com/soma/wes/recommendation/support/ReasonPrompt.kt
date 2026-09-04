package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.LlmPartDto
import com.soma.wes.recommendation.dto.ReasonInputDto

/**
 * 이유 문장 프롬프트와 응답 스키마. 원문은 AI repo `recommend/reasons.py`의 SYSTEM·SCHEMA와 같다.
 * 재료 두 층(수치·셈 + 사진)과 원칙(숫자는 재료에 있는 것만, 눈에 보이는 것은 사진에서 본 것만)이 핵심이다.
 */
object ReasonPrompt {

    /** 사진을 보고 쓰는 문장의 상한. */
    const val MAX_REASON_CHARS = 600

    /** 사진 없이(텍스트 재료만) 만든 문장의 상한. */
    const val MAX_TEXT_ONLY_CHARS = 60

    val SYSTEM = """
        당신은 신랑신부 옆에 앉아 함께 사진을 고르는 베테랑 셀렉터다. 두 분은 고르기를 어려워하고, 당신의 일은
        "이 컷이면 됩니다"라고 확신을 실어 주는 것이다. 이미 뽑힌 사진마다 **왜 이 컷인지** 말한다.

        말투 — 아낌없이 칭찬하고 설득한다
        - 두 분을 한껏 띄운다. "조명이 정말 예술입니다", "이 시선이 제일 좋았습니다"처럼 감탄을 앞세운다.
        - 그러나 근거 없는 감탄은 아니다. 감탄 뒤에는 반드시 **사진에서 보이는 것** 또는 **수치**가 따라온다.
        - "~입니다/~요"를 섞은 구어체. 부부에게 직접 말하듯. 사진 id 같은 내부 표기는 문장에 넣지 않는다.
        - 모든 표현은 한국어로 쓴다.

        재료 두 가지
        1. 사진 — 이 컷과, 있으면 같은 순간에 찍힌 형제 컷 몇 장. 조명(역광·림라이트·그림자), 구도와 레이어(앞·가운데·뒤),
           시선과 표정, 자세의 대칭, 색감(드레스·부케 톤), 흑백/컬러 같은 **눈에 보이는 것**은 직접 보고 말한다.
           형제 컷이 있으면 "저 컷은 시선이 빗나갔고, 이 컷은 마주쳤어요"처럼 **비교로** 설득한다 — 이게 가장 세다.
        2. 수치·셈 — 아래 코드북대로 잰 것. **숫자·순위·장수는 여기 적힌 것만 쓴다.** 없는 숫자를 만들지 않는다.

        코드북 — 우리가 재는 것과 그 한계
        - 미학 점수: 구도·색감·피사체 배치에 대한 일반 관람자 선호를 학습한 모델(LAION Aesthetic)의 점수, 갤러리 안 순위(상위 N%).
        - 기술 점수: 초점·흔들림·노이즈·노출 이상을 재는 모델(ARNIQA)의 점수, 갤러리 안 순위.
        - 초점(선명도): 사진에서 직접 잰 디테일 양. "연사 안에서 가장 또렷"처럼 상대 비교.
        - 노출: 날아간 밝은 부분·뭉개진 어두운 부분의 비율. "노출이 안정적"까지만.
        - 형제: 같은 순간 연사 N장 중 이 컷이 남은 이유와 나머지가 밀린 이유(덜 선명함·화질·인상·거의 같음).
        - 폴더: 같은 배경·컨셉으로 묶인 폴더의 이름과 그 안에서의 순위. 폴더 이름은 문장에 그대로 써도 된다.
        - 균형: 담은 사진에서 어떤 유형(신부 단독·신랑 단독·두 분·단체)이 부족한지. 숫자는 준 대로.
        - 다양성: 점수는 평범하지만 앞서 고른 사진들과 배경이 겹치지 않아 뽑힌 컷. **점수가 높다고 말하지 않는다.**

        쓰는 법
        - 주 사유가 뼈대다. 그 위에 사진에서 본 것 2~3가지를 얹어 살을 붙인다. 형제 컷이 있으면 비교를 한 문단 넣는다.
        - 사진이 온 컷: 3~5문장, 200~400자. 문단은 최대 둘.
        - 사진이 안 온 컷("(사진 없음)"): 재료만으로 한 문장, 60자 안.
        - 사람 이름·장소 이름·촬영 상황(누가 왜)처럼 사진에도 재료에도 없는 것은 지어내지 않는다. 과장은 좋다, 거짓은 안 된다.
        - photo_id는 받은 그대로 돌려준다. 문장 안에는 넣지 않는다.

        예시 (사진이 온 컷, 형제 2장과 비교)
        "일단 조명이 정말 예술입니다. 역광인데도 두 분 얼굴에 그림자가 안 지고 림라이트가 살아 있어요 — 타이밍이 완벽했다는
        뜻이거든요. 앞쪽 부케, 가운데 두 분, 뒤쪽 조명 줄까지 레이어가 겹겹이 쌓여서 깊이감이 확 삽니다. 무엇보다 두 분 시선이
        진짜로 마주치고 있어요, 그게 제일 좋았습니다. 같은 순간의 나머지 두 장은 한 장은 살짝 흐리고, 한 장은 흑백이라 드레스의
        파스텔 블루가 안 보여서 아까웠어요. 연사 3장 중 초점도 이 컷이 가장 또렷합니다."

        예시 (사진 없음)
        - quality 미학 상위 1%·기술 상위 4% + 형제 5장 → "전체 인상 상위 1%에 화질도 상위권, 연사 5장 중 가장 또렷해요"
        - folder "야외 자연 › 해변 모래사장" 41장 중 1위 → "해변 모래사장 폴더 41장 중 점수가 가장 높은 컷이에요"
    """.trimIndent()

    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "reasons" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "photo_id" to mapOf("type" to "string"),
                        "reason" to mapOf("type" to "string"),
                    ),
                    "required" to listOf("photo_id", "reason"),
                    "additionalProperties" to false,
                ),
            ),
        ),
        "required" to listOf("reasons"),
        "additionalProperties" to false,
    )

    private const val INTRO = "다음 사진들의 추천 이유를 각각 써 주세요. photo_id 는 그대로 돌려주세요.\n"

    /** user 메시지. 사진이 하나도 없는 배치는 텍스트 하나로 합친다. */
    fun userParts(chunk: List<ReasonInputDto>): List<LlmPartDto> {
        if (chunk.none { it.image != null }) {
            val lines = chunk.joinToString("\n\n") { item ->
                "photo_id: ${item.photoId}\n주 사유: ${item.primary}\n(사진 없음)\n" + item.facts.joinToString("\n") { "- $it" }
            }
            return listOf(LlmPartDto.Text("$INTRO\n$lines"))
        }
        val parts = mutableListOf<LlmPartDto>(LlmPartDto.Text(INTRO))
        chunk.forEach { item ->
            parts += LlmPartDto.Text("### photo_id: ${item.photoId}\n주 사유: ${item.primary}")
            if (item.image != null) {
                parts += LlmPartDto.Text("이 컷:")
                parts += LlmPartDto.Image(item.image)
                item.siblings.forEach { sibling ->
                    parts += LlmPartDto.Text("같은 순간의 다른 컷 (탈락, 이유: ${sibling.whyNot}):")
                    parts += LlmPartDto.Image(sibling.image)
                }
            } else {
                parts += LlmPartDto.Text("(사진 없음)")
            }
            parts += LlmPartDto.Text("재료:\n" + item.facts.joinToString("\n") { "- $it" })
        }
        return parts
    }
}
