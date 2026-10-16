package com.soma.wes.recommendation.infrastructure

import com.soma.wes.recommendation.dto.LlmJsonRequestDto
import com.soma.wes.recommendation.dto.LlmPartDto
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.service.port.StructuredLlmClient
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.Base64

/**
 * OpenAI 호환 `/chat/completions`의 구조화 출력(`response_format=json_schema`) — 로컬 개발·측정용
 * (`scripts/llm`의 codex-lb 게이트웨이, `app.llm.provider=openai`). 이미지는 data URL JPEG다.
 *
 * 재시도는 Bedrock 어댑터와 같다 — 요청의 [LlmJsonRequestDto.maxRetries]만큼 429에 한해 돈다. 구독 계정 쿼터가
 * 바닥나면 429가 이어지므로 결국 실패로 알린다. 호출별 타임아웃은 RestClient의 읽기 타임아웃(30초)이 대신한다.
 */
class OpenAiStructuredLlmClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val model: String,
) : StructuredLlmClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override val isEnabled: Boolean = true

    companion object {

        private const val COMPLETIONS_PATH = "/chat/completions"
        private const val SCHEMA_NAME = "output"
        private const val FINISH_LENGTH = "length"
        private const val THROTTLE_BACKOFF_MILLIS = 500L
    }

    override fun completeJson(request: LlmJsonRequestDto): JsonNode {
        val body = objectMapper.writeValueAsString(requestBody(request))

        var attempt = 0
        while (true) {
            try {
                val response = restClient.post()
                    .uri(COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String::class.java)
                return parse(objectMapper.readTree(response ?: "{}"))
            } catch (e: RestClientResponseException) {
                if (e.statusCode.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS) && attempt < request.maxRetries) {
                    attempt++
                    log.warn("OpenAI 호환 429, 재시도 {}/{}: model={}", attempt, request.maxRetries, model)
                    Thread.sleep(THROTTLE_BACKOFF_MILLIS * attempt)
                    continue
                }
                log.error("OpenAI 호환 호출 실패: model={}, status={}, body={}", model, e.statusCode, e.responseBodyAsString.take(300))
                throw RecommendationException(RecommendationErrorCode.LLM_CALL_FAILED)
            } catch (e: RestClientException) {
                log.warn("OpenAI 호환 호출 실패: model={}, cause={}", model, e.message)
                throw RecommendationException(RecommendationErrorCode.LLM_CALL_FAILED)
            }
        }
    }

    private fun requestBody(request: LlmJsonRequestDto): ObjectNode {
        val body = objectMapper.createObjectNode()
        body.put("model", model)
        body.put("max_completion_tokens", request.maxTokens)

        val messages = body.putArray("messages")
        messages.addObject().put("role", "system").put("content", request.system)
        val content = messages.addObject().put("role", "user").putArray("content")
        for (part in request.parts) {
            when (part) {
                is LlmPartDto.Text -> content.addObject().put("type", "text").put("text", part.text)
                is LlmPartDto.Image -> content.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(part.jpeg)}")
            }
        }

        val schema = body.putObject("response_format").put("type", "json_schema").putObject("json_schema")
        schema.put("name", SCHEMA_NAME)
        schema.set("schema", objectMapper.valueToTree<JsonNode>(request.schema))
        return body
    }

    private fun parse(root: JsonNode): JsonNode {
        val choice = root.path("choices").path(0)
        val finishReason = choice.path("finish_reason").asString(null)
        val text = choice.path("message").path("content").asString(null)
        log.info(
            "openai {} · in {} / out {} tokens · finish={}",
            model, root.path("usage").path("prompt_tokens").asInt(0), root.path("usage").path("completion_tokens").asInt(0), finishReason,
        )
        if (finishReason == FINISH_LENGTH || text.isNullOrBlank()) {
            log.warn("OpenAI 호환 응답을 쓸 수 없음: finish={}, hasText={}", finishReason, !text.isNullOrBlank())
            throw RecommendationException(RecommendationErrorCode.LLM_CALL_FAILED)
        }
        return try {
            objectMapper.readTree(text)
        } catch (e: JacksonException) {
            log.warn("OpenAI 호환 응답이 JSON이 아님: {}", text.take(200))
            throw RecommendationException(RecommendationErrorCode.LLM_CALL_FAILED)
        }
    }
}
