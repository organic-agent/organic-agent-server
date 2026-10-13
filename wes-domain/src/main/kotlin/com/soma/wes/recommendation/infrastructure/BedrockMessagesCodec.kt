package com.soma.wes.recommendation.infrastructure

import com.soma.wes.recommendation.dto.LlmJsonRequestDto
import com.soma.wes.recommendation.dto.LlmPartDto
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * Anthropic Messages(InvokeModel) 요청·응답 JSON. AI repo의 `anthropic` SDK가 보내던 body와 키 단위로 같다 —
 * `output_config.format.json_schema`가 스키마 강제이고, 이미지는 base64 JPEG 블록이다.
 */
@Component
class BedrockMessagesCodec(
    private val objectMapper: ObjectMapper,
) {

    fun encode(request: LlmJsonRequestDto): String {
        val body = objectMapper.createObjectNode()
        body.put("anthropic_version", ANTHROPIC_VERSION)
        body.put("max_tokens", request.maxTokens)
        body.put("system", request.system)

        val content = body.putArray("messages").addObject().put("role", "user").putArray("content")
        request.parts.forEach { part ->
            when (part) {
                is LlmPartDto.Text -> content.addObject().put("type", "text").put("text", part.text)
                is LlmPartDto.Image -> content.addObject().put("type", "image").putObject("source")
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", Base64.getEncoder().encodeToString(part.jpeg))
            }
        }

        val format = body.putObject("output_config").putObject("format")
        format.put("type", "json_schema")
        format.set("schema", objectMapper.valueToTree<JsonNode>(request.schema))

        return objectMapper.writeValueAsString(body)
    }

    fun decode(json: String): Decoded {
        val root = objectMapper.readTree(json)
        val text = root.path("content").firstOrNull { it.path("type").asText() == "text" }?.path("text")?.asText()
        return Decoded(
            stopReason = root.path("stop_reason").asText(null),
            text = text,
            inputTokens = root.path("usage").path("input_tokens").asInt(0),
            outputTokens = root.path("usage").path("output_tokens").asInt(0),
        )
    }

    fun parseJson(text: String): JsonNode = objectMapper.readTree(text)

    data class Decoded(
        val stopReason: String?,
        val text: String?,
        val inputTokens: Int,
        val outputTokens: Int,
    )

    companion object {
        const val ANTHROPIC_VERSION = "bedrock-2023-05-31"
    }
}
