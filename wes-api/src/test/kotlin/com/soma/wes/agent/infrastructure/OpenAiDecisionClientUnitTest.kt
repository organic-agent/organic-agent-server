package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

/** OpenAI 호환 게이트웨이(`scripts/llm`) 판단기의 요청 모양과 응답 해석. 게이트웨이는 띄우지 않는다. */
class OpenAiDecisionClientUnitTest {

    private val builder = RestClient.builder().baseUrl(BASE_URL)
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = OpenAiDecisionClient(
        restClient = builder.build(),
        objectMapper = JsonMapper.builder().build(),
        model = "codex-terra",
        reasoningEffort = "low",
    )

    private val context = AgentContextDto(
        concepts = listOf(
            AgentContextDto.Concept(
                id = 2,
                name = "야외정원",
                shotOrder = 1,
                timeRange = null,
                photoCount = 40,
                details = listOf(AgentContextDto.Detail(id = 21, name = "노을", photoCount = 40)),
            ),
        ),
        selectedCount = 0,
        maxSelectablePhotoCount = null,
        screen = null,
    )

    companion object {

        private const val BASE_URL = "http://gateway/v1"
    }

    @Test
    fun `도구 하나를 강제해 묻고 고른 도구의 인자를 주문서로 옮긴다`() {
        // given
        server.expect(requestTo("$BASE_URL/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.model").value("codex-terra"))
            .andExpect(jsonPath("$.tool_choice").value("required"))
            .andExpect(jsonPath("$.tools.length()").value(AgentDecisionTools.definitions.size))
            .andExpect(jsonPath("$.messages[1].content").value("노을에서 남편만 보여줘"))
            .andRespond(withSuccess(toolCallResponse("filter_photos", """{"filter":{"details":[21],"subjects":["groom"]}}"""), MediaType.APPLICATION_JSON))

        // when
        val decision = client.decide("노을에서 남편만 보여줘", context)

        // then
        server.verify()
        assertSoftly { softly ->
            softly.assertThat(decision).isInstanceOf(AgentDecisionDto.FilterPhotos::class.java)
            softly.assertThat((decision as AgentDecisionDto.FilterPhotos).filter.detailFolderIds).containsExactly(21L)
            softly.assertThat(decision.filter.subjects).containsExactly(AgentSubject.GROOM)
        }
    }

    @Test
    fun `도구 없이 글로만 답하면 그 글로 되묻는다`() {
        // given
        server.expect(requestTo("$BASE_URL/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"어느 폴더를 말씀하시는 건가요?"}}]}""", MediaType.APPLICATION_JSON))

        // when
        val decision = client.decide("그거 보여줘", context)

        // then
        assertThat(decision).isEqualTo(AgentDecisionDto.Clarify("어느 폴더를 말씀하시는 건가요?"))
    }

    @Test
    fun `게이트웨이가 실패하면 판단할 수 없다고 알린다`() {
        // given
        server.expect(requestTo("$BASE_URL/chat/completions")).andRespond(withServerError())

        // when & then
        assertThatThrownBy { client.decide("몇 장 골랐어?", context) }
            .isInstanceOf(AgentException::class.java)
            .extracting("errorCode")
            .isEqualTo(AgentErrorCode.DECISION_UNAVAILABLE)
    }

    private fun toolCallResponse(name: String, arguments: String): String {
        val escaped = arguments.replace("\"", "\\\"")
        return """
            {"choices":[{"message":{"content":null,"tool_calls":[{"type":"function","function":{"name":"$name","arguments":"$escaped"}}]}}],
             "usage":{"prompt_tokens":1200,"completion_tokens":40}}
        """.trimIndent()
    }
}
