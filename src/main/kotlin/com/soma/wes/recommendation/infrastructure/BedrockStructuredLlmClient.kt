package com.soma.wes.recommendation.infrastructure

import com.soma.wes.recommendation.config.LlmProperties
import com.soma.wes.recommendation.dto.LlmJsonRequestDto
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.service.StructuredLlmClient
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode

/**
 * Bedrock InvokeModel로 Anthropic Messages를 부른다. AI repo `llm.BedrockClient`의 자리다.
 *
 * Mantle(Messages 전용 엔드포인트)은 서울에 없어 legacy InvokeModel 경로다 — 이 경로에서도
 * `output_config` 스키마 강제가 동작함은 AI repo가 실호출로 확인했다(이미지 블록 포함, 2026-08-30).
 * 재시도는 요청의 [LlmJsonRequestDto.maxRetries]만큼 스로틀링(429)에 한해 돈다 — 타임아웃·거부·잘림은
 * 다시 불러도 같으므로 즉시 실패로 알린다.
 */
class BedrockStructuredLlmClient(
    private val client: BedrockRuntimeClient,
    private val properties: LlmProperties,
    private val codec: BedrockMessagesCodec,
) : StructuredLlmClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override val isEnabled: Boolean = true

    override fun completeJson(request: LlmJsonRequestDto): JsonNode {
        val body = codec.encode(request)
        val invoke = InvokeModelRequest.builder()
            .modelId(properties.modelId)
            .contentType("application/json")
            .accept("application/json")
            .body(SdkBytes.fromUtf8String(body))
            .overrideConfiguration { override -> request.timeout?.let { override.apiCallTimeout(it) } }
            .build()

        var attempt = 0
        while (true) {
            try {
                val response = client.invokeModel(invoke)
                return parse(codec.decode(response.body().asUtf8String()))
            } catch (e: BedrockRuntimeException) {
                if (e.isThrottlingException && attempt < request.maxRetries) {
                    attempt++
                    log.warn("Bedrock 스로틀링, 재시도 {}/{}: model={}", attempt, request.maxRetries, properties.modelId)
                    Thread.sleep(THROTTLE_BACKOFF_MILLIS * attempt)
                    continue
                }
                log.error("Bedrock 호출 실패: model={}, status={}", properties.modelId, e.statusCode(), e)
                throw RecommendationException(RecommendationErrorCode.LLM_CALL_FAILED)
            } catch (e: SdkException) {
                // ApiCallTimeoutException 포함 — 예산 초과는 호출자가 템플릿으로 흡수한다.
                log.warn("Bedrock 호출 실패: model={}, cause={}", properties.modelId, e.message)
                throw RecommendationException(RecommendationErrorCode.LLM_CALL_FAILED)
            }
        }
    }

    private fun parse(decoded: BedrockMessagesCodec.Decoded): JsonNode {
        log.info(
            "bedrock {} · in {} / out {} tokens · stop={}",
            properties.modelId, decoded.inputTokens, decoded.outputTokens, decoded.stopReason,
        )
        if (decoded.stopReason == STOP_REFUSAL || decoded.stopReason == STOP_MAX_TOKENS || decoded.text == null) {
            log.warn("Bedrock 응답을 쓸 수 없음: stop={}, hasText={}", decoded.stopReason, decoded.text != null)
            throw RecommendationException(RecommendationErrorCode.LLM_CALL_FAILED)
        }
        return try {
            codec.parseJson(decoded.text)
        } catch (e: JacksonException) {
            log.warn("Bedrock 응답이 JSON이 아님: {}", decoded.text.take(200))
            throw RecommendationException(RecommendationErrorCode.LLM_CALL_FAILED)
        }
    }

    companion object {
        private const val STOP_REFUSAL = "refusal"
        private const val STOP_MAX_TOKENS = "max_tokens"
        private const val THROTTLE_BACKOFF_MILLIS = 500L
    }
}
