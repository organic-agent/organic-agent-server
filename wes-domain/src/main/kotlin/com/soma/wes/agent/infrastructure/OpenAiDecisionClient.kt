package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import com.soma.wes.agent.service.port.DecisionClient
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * OpenAI 호환 `/chat/completions` 판단기 — 로컬 개발·측정용(`scripts/llm`의 codex-lb 게이트웨이, `app.llm.provider=openai`).
 * 운영 판단기([ConverseDecisionClient])와 같은 시스템 프롬프트·도구 정의를 보내고 같은 [AgentDecisionReader]로 주문서를 만든다.
 * `tool_choice=required`로 도구 하나를 강제한다.
 */
class OpenAiDecisionClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val model: String,
    private val reasoningEffort: String,
) : DecisionClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val reader = AgentDecisionReader(objectMapper)
    private val tools = toolsJson()

    companion object {

        private const val COMPLETIONS_PATH = "/chat/completions"
        private const val TOOL_CHOICE_REQUIRED = "required"
        private const val NANOS_PER_MILLI = 1_000_000
    }

    override fun decide(question: String, context: AgentContextDto): AgentDecisionDto {
        val body = requestBody(question, context)

        val startedAt = System.nanoTime()
        val response = runCatching {
            restClient.post()
                .uri(COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String::class.java)
        }.getOrElse { e ->
            log.warn("OpenAI 호환 판단 실패: model={}, error={}", model, e.toString())
            throw AgentException(AgentErrorCode.DECISION_UNAVAILABLE)
        }
        val root = objectMapper.readTree(response ?: "{}")
        val message = root.path("choices").path(0).path("message")
        val toolCall = message.path("tool_calls").path(0).path("function")
        val usage = root.path("usage")
        log.info(
            "OpenAI 호환 판단: {}ms, in={}, out={}, tool={}",
            (System.nanoTime() - startedAt) / NANOS_PER_MILLI,
            usage.path("prompt_tokens").asInt(0),
            usage.path("completion_tokens").asInt(0),
            toolCall.path("name").asString(null),
        )

        return reader.read(
            toolName = toolCall.path("name").asString(null),
            arguments = toolCall.path("arguments").asString(null),
            text = message.path("content").asString(null),
            context = context,
        )
    }

    private fun requestBody(question: String, context: AgentContextDto): ObjectNode {
        val body = objectMapper.createObjectNode()
        body.put("model", model)
        body.put("reasoning_effort", reasoningEffort)
        body.put("tool_choice", TOOL_CHOICE_REQUIRED)
        body.put("parallel_tool_calls", false)
        body.set("tools", tools)
        val messages = body.putArray("messages")
        messages.addObject().put("role", "system").put("content", AgentDecisionPrompt.system(context, objectMapper))
        messages.addObject().put("role", "user").put("content", question)
        return body
    }

    private fun toolsJson(): JsonNode {
        val array = objectMapper.createArrayNode()
        for (definition in AgentDecisionTools.definitions) {
            val function = array.addObject().put("type", "function").putObject("function")
            function.put("name", definition.name)
            function.put("description", definition.description)
            function.set("parameters", objectMapper.readTree(definition.inputSchema))
        }
        return array
    }
}
