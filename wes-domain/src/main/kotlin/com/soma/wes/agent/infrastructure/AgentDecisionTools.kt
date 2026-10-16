package com.soma.wes.agent.infrastructure

import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * 판단기에게 보여 주는 도구 다섯 개. 정의(이름·설명·입력 모양)만 주고 실행하지 않는다 — 모델이 고른 도구와 인자가 곧 주문서다.
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
            "burstRep": {"type": "boolean", "description": "true=비슷한 연사 묶음마다 대표 1장만. '베스트 컷·대표 컷·잘 나온 것·한 장씩만'은 이것으로 거른다(못 거르는 조건이 아니다)"}
          }
        }
    """.trimIndent()

    private val UNSUPPORTED_SCHEMA = """
        {"type": "array", "items": {"enum": ["person", "face", "prop", "composition"]},
         "description": "요청에 있지만 지금 데이터로 거를 수 없는 조건: person=특정 인물(부모님 등), face=눈감김·표정, prop=소품(베일·반지 등, 폴더 이름으로 못 찾을 때), composition=구도(전신·클로즈업)"}
    """.trimIndent()

    val callbacks: List<ToolCallback> = listOf(
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
                '컨셉별·카테고리별'은 by_concept, '세부 폴더별'은 by_detail, '누가 나왔나·인물 비율'은 by_subjects, '비슷한 거·중복'은 duplicates.
            """.trimIndent(),
            schema = """
                {"type": "object", "properties": {
                  "metric": {"enum": ["count", "by_concept", "by_detail", "by_subjects", "by_orientation", "duplicates"]},
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
            name = ASK_CLARIFICATION,
            description = "무엇을 원하는지 불명확하거나 할 수 없는 일이면 사용자에게 존댓말 한 문장으로 되묻거나 알린다. 이모지는 쓰지 않는다.",
            schema = """
                {"type": "object", "properties": {"question": {"type": "string"}}, "required": ["question"]}
            """.trimIndent(),
        ),
    )

    private fun tool(name: String, description: String, schema: String): ToolCallback =
        DefinitionOnlyToolCallback(ToolDefinition.builder().name(name).description(description).inputSchema(schema).build())

    /** 정의만 싣는다. Spring AI 2.0의 `BedrockProxyChatModel`은 도구를 스스로 실행하지 않고(실행은 `ChatClient` 어드바이저 몫) 고른 도구만 돌려준다. */
    private class DefinitionOnlyToolCallback(private val definition: ToolDefinition) : ToolCallback {

        override fun getToolDefinition(): ToolDefinition = definition

        override fun call(toolInput: String): String = error("판단기 도구는 정의만 있다 — 실행되면 안 된다: ${definition.name()}")

        override fun call(toolInput: String, toolContext: ToolContext?): String = call(toolInput)
    }
}
