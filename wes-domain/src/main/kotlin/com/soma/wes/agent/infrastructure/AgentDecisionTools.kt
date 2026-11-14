package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.domain.ExplainTopic
import com.soma.wes.agent.dto.AgentToolDefinitionDto

/**
 * 판단기에게 보여 주는 도구 여섯 개. 정의(이름·설명·입력 모양)만 주고 실행하지 않는다 — 모델이 고른 도구와 인자가 곧 주문서다.
 *
 * 설명 문장이 정확도를 좌우한다(AI repo 기준선: 오답 대부분이 집계 범위·단위 선택). 문장을 바꾸면 질문 세트로 다시 잰다.
 * 모양은 AI repo 프로토타입(`agent_eval.py`)과 같고, 신뢰도(confidence)는 뺐다 — 기준선에서 오답을 거르지 못했다.
 */
object AgentDecisionTools {

    const val FIND_FOLDER = "find_folder"
    const val FILTER_PHOTOS = "filter_photos"
    const val STATS = "stats"
    const val PLAN_ACTION = "plan_action"
    const val ASK_CLARIFICATION = "ask_clarification"
    const val EXPLAIN = "explain"

    private val FILTER_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "concepts": {"type": "array", "items": {"type": "integer"}, "description": "컨셉 폴더 id(c 뒤 숫자)"},
            "details": {"type": "array", "items": {"type": "integer"}, "description": "세부 폴더 id(d 뒤 숫자)"},
            "subjects": {"type": "array", "items": {"enum": ["bride", "groom", "couple", "group"]},
                         "description": "사진 피사체: bride=신부 단독, groom=신랑 단독, couple=둘이 함께, group=단체"},
            "selected": {"type": "boolean", "description": "true=이미 고른 사진, false=아직 안 고른 사진"},
            "orientation": {"enum": ["landscape", "portrait"]},
            "burstRep": {"type": "boolean", "description": "true=비슷한 연사 묶음마다 대표 1장만. '베스트 컷·대표 컷·잘 나온 것·한 장씩만'은 이것으로 거른다(못 거르는 조건이 아니다)"},
            "likedByGuests": {"type": "boolean", "description": "true=가족·하객이 좋아요 누른 사진만, false=아무도 안 누른 사진만"},
            "hasComments": {"type": "boolean", "description": "true=댓글 달린 사진만, false=댓글 없는 사진만"},
            "minRating": {"type": "integer", "minimum": 1, "maximum": 5, "description": "별점이 이 값 이상인 사진만. '별 5개 준 거'=5, '별 4개 이상'=4"},
            "maxRating": {"type": "integer", "minimum": 1, "maximum": 5, "description": "별점이 이 값 이하인 사진만. '1점짜리'=minRating 1 + maxRating 1, '2점 이하'=2"},
            "hasMemo": {"type": "boolean", "description": "true=메모 남긴 사진만, false=메모 없는 사진만"},
            "addedBy": {"enum": ["me", "partner"], "description": "이 사람이 선택에 담은 사진만. me=말하는 사람, partner=배우자(남편·아내)"},
            "ratedBy": {"enum": ["me", "partner"], "description": "이 사람이 (마지막으로) 별점을 준 사진만"},
            "memoBy": {"enum": ["me", "partner"], "description": "이 사람이 (마지막으로) 메모를 쓴 사진만"},
            "commentedBy": {"enum": ["me", "partner"], "description": "이 사람이 댓글을 단 사진만"}
          }
        }
    """.trimIndent()

    private val UNSUPPORTED_SCHEMA = """
        {"type": "array", "items": {"enum": ["person", "face", "prop", "composition"]},
         "description": "요청에 있지만 지금 데이터로 거를 수 없는 조건: person=특정 인물(부모님 등), face=눈감김·웃음 등 표정, prop=소품(베일·반지 등, 폴더 이름으로 못 찾을 때), composition=구도(전신·클로즈업)"}
    """.trimIndent()

    val definitions: List<AgentToolDefinitionDto> = listOf(
        tool(
            name = FIND_FOLDER,
            description = "원하는 폴더를 찾아 화면에서 연다.",
            schema = """
                {"type": "object", "properties": {
                  "targets": {"type": "array", "items": {"type": "string"}, "description": "'c<id>'(컨셉) 또는 'd<id>'(세부), 가장 맞는 것부터"}
                }, "required": ["targets"]}
            """.trimIndent(),
        ),
        tool(
            name = FILTER_PHOTOS,
            description = "조건에 맞는 사진만 화면에 보여 준다(사진은 옮기지 않음).",
            schema = """
                {"type": "object", "properties": {"filter": $FILTER_SCHEMA, "unsupported": $UNSUPPORTED_SCHEMA}, "required": ["filter"]}
            """.trimIndent(),
        ),
        tool(
            name = STATS,
            description = """
                고른 사진(scope=selected) 또는 갤러리 전체(scope=gallery)를 센다. 셀렉 화면이라 범위를 말하지 않으면 고른 사진이다.
                '컨셉별·카테고리별'은 by_concept, '세부 폴더별'은 by_detail, '누가 나왔나·인물 비율'은 by_subjects, '비슷한 거·중복'은 duplicates,
                '별점별·별 몇 개씩'은 by_rating.
            """.trimIndent(),
            schema = """
                {"type": "object", "properties": {
                  "metric": {"enum": ["count", "by_concept", "by_detail", "by_subjects", "by_orientation", "by_rating", "duplicates"]},
                  "scope": {"enum": ["selected", "gallery"]},
                  "filter": $FILTER_SCHEMA,
                  "unsupported": $UNSUPPORTED_SCHEMA
                }, "required": ["metric", "scope"]}
            """.trimIndent(),
        ),
        tool(
            name = PLAN_ACTION,
            description = "사진을 선택에 넣기(select_add)·빼기(select_remove)·세부 폴더로 옮기기(move) 계획을 만든다. 사용자가 확인해야 실행된다.",
            schema = """
                {"type": "object", "properties": {
                  "op": {"enum": ["select_add", "select_remove", "move"]},
                  "filter": $FILTER_SCHEMA,
                  "target": {"type": "integer", "description": "move 의 대상 세부 폴더 id(d 뒤 숫자)"},
                  "unsupported": $UNSUPPORTED_SCHEMA
                }, "required": ["op", "filter"]}
            """.trimIndent(),
        ),
        tool(
            name = EXPLAIN,
            description = """
                사용법이나 지금 상태를 물으면 주제 하나를 고른다 — 서버가 지금 갤러리 상태로 답을 채운다.
                submit=제출 방법·제출 가능 여부, withdraw=제출 되돌리기, deadline=선택 마감 언제까지, max_photos=계약 장수·몇 장 남았나,
                retouch_rounds=보정 몇 번·보정 요청 방법, share_family=가족·부모님에게 보여 주기, rating=별점 매기기, memo=메모, comment=댓글,
                delete_photo=사진 지우기, download=원본·파일 내려받기, ai_recommend=AI 추천이 뭔지·요청 방법, unknown=이 목록 밖의 사용법.
                사진을 거르거나 세는 말이 아니라 "어떻게 해?"·"언제까지?"·"몇 번 남았어?"처럼 방법·상태를 묻는 말에 쓴다.
            """.trimIndent(),
            schema = """
                {"type": "object", "properties": {"topic": {"enum": ${topicCodes()}}}, "required": ["topic"]}
            """.trimIndent(),
        ),
        tool(
            name = ASK_CLARIFICATION,
            description = "무엇을 원하는지 불명확하거나 할 수 없는 일이면 사용자에게 존댓말 한 문장으로 되묻거나 알린다. 이모지는 쓰지 않는다.",
            schema = """
                {"type": "object", "properties": {"question": {"type": "string"}}, "required": ["question"]}
            """.trimIndent(),
        ),
    )

    private fun topicCodes(): String = ExplainTopic.entries.joinToString(", ", "[", "]") { "\"${it.code}\"" }

    private fun tool(name: String, description: String, schema: String) =
        AgentToolDefinitionDto(name = name, description = description, inputSchema = schema)
}
