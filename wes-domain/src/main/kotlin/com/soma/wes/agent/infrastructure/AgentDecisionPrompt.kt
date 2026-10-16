package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.dto.AgentContextDto
import tools.jackson.databind.ObjectMapper

/**
 * 판단기의 시스템 프롬프트. 폴더 목록·촬영 순서·고른 장수·지금 화면을 싣는다. 사진 id는 싣지 않는다.
 *
 * 말하는 사람이 신랑인지 신부인지는 모른다(저장하지 않기로 함, 계획 문서 D3). "남편·아내"는 그대로 풀리고,
 * "나만·저 혼자"처럼 화자 자신을 가리킬 때만 되묻는다 — 질문 세트에서 6~7%다.
 */
object AgentDecisionPrompt {

    fun system(context: AgentContextDto, objectMapper: ObjectMapper): String =
        "${stable(context, objectMapper)}\n${volatile(context, objectMapper)}"

    /** 지침과 폴더 목록. 같은 갤러리면 질문마다 같다 — 프롬프트 캐시의 앞부분이 된다. */
    fun stable(context: AgentContextDto, objectMapper: ObjectMapper): String {
        val folders = context.concepts.map { concept ->
            mapOf(
                "id" to "c${concept.id}",
                "name" to concept.name,
                "shot_order" to concept.shotOrder,
                "time" to concept.timeRange,
                "photos" to concept.photoCount,
                "details" to concept.details.map { detail ->
                    mapOf("id" to "d${detail.id}", "name" to detail.name, "photos" to detail.photoCount)
                },
            )
        }

        return """
            당신은 웨딩 사진 셀렉 서비스의 도우미다. 부부(신랑·신부)가 스튜디오 촬영 사진을 고르고 있다.
            반드시 도구 하나를 골라 부른다. 글로만 답하지 않는다.
            누가 말하는지는 모른다. "남편·신랑"은 groom, "아내·와이프·신부"는 bride, "우리 둘·같이·커플"은 couple이다.
            "나만·저 혼자·내 사진"처럼 말하는 사람 자신이 나온 사진을 가리키면 신랑인지 신부인지 ask_clarification으로 묻는다.
            "내가 고른·제가 고른"은 부부가 함께 고르는 사진(selected=true)이라 묻지 않는다.
            이전 대화는 없다 — "아까·저번·그거"처럼 가리키는 대상을 알 수 없으면 되묻는다. "지금 보이는 거·여기"는 아래 지금 화면이다.
            지금 데이터로 거를 수 없는 조건은 unsupported에 넣는다(폴더 이름으로 대신하지 말 것, 단 사용자가 폴더를 지목했으면 폴더로).
            못 거르는 조건만 있어도 원래 하려던 일의 도구를 고른다 — 있는지·몇 장인지 물으면 stats, 넣거나 빼라면 plan_action.
            사진 넣기·빼기·옮기기는 plan_action으로 계획만 만든다. 제출·삭제·별점은 할 수 없다고 ask_clarification으로 알린다.
            폴더 목록(shot_order = 촬영 순서, id의 c=컨셉 d=세부):
            ${objectMapper.writeValueAsString(folders)}
        """.trimIndent()
    }

    /** 고른 장수·지금 화면. 질문마다 바뀌어 캐시 뒤에 둔다. */
    fun volatile(context: AgentContextDto, objectMapper: ObjectMapper): String {
        val limit = context.maxSelectablePhotoCount?.let { "계약 ${it}장" } ?: "계약 장수 없음"
        return """
            고른 사진: ${context.selectedCount}장 / $limit
            지금 화면: ${screen(context.screen, objectMapper)}
        """.trimIndent()
    }

    private fun screen(screen: AgentContextDto.Screen?, objectMapper: ObjectMapper): String {
        if (screen == null) return "갤러리 전체"
        val parts = listOfNotNull(
            screen.conceptFolderId?.let { "c$it 폴더" },
            screen.detailFolderId?.let { "d$it 폴더" },
            screen.filter?.let { "필터 ${objectMapper.writeValueAsString(it)}" },
        )
        return if (parts.isEmpty()) "갤러리 전체" else parts.joinToString(", ")
    }
}
