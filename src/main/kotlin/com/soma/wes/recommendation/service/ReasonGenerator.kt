package com.soma.wes.recommendation.service

import com.soma.wes.recommendation.config.LlmProperties
import com.soma.wes.recommendation.dto.LlmJsonRequestDto
import com.soma.wes.recommendation.dto.ReasonInputDto
import com.soma.wes.recommendation.support.ReasonPrompt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * photo_id → 이유 문장. AI repo `reasons.generate`의 자리다 — 배치마다 LLM을 부르고, 실패·누락·빈 문장·상한 초과는
 * 그 사진의 템플릿([ReasonInputDto.fallback])으로 채운다. 한 배치의 실패가 라운드를 죽이지 않는다.
 */
@Service
class ReasonGenerator(
    private val llm: StructuredLlmClient,
    private val properties: LlmProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun generate(items: List<ReasonInputDto>): Map<Long, String> {
        val out = items.associate { it.photoId to it.fallback }.toMutableMap()
        if (!llm.isEnabled || items.isEmpty()) return out

        items.chunked(properties.reasonsBatch).forEach { chunk ->
            val byId = chunk.associateBy { it.photoId }
            val response = try {
                llm.completeJson(
                    LlmJsonRequestDto(
                        system = ReasonPrompt.SYSTEM,
                        parts = ReasonPrompt.userParts(chunk),
                        schema = ReasonPrompt.SCHEMA,
                        maxTokens = properties.reasonsMaxTokens,
                        timeout = properties.reasonsTimeout,
                        maxRetries = BATCH_RETRIES,
                    ),
                )
            } catch (e: Exception) {
                log.warn("이유 문장 호출 실패 ({}장 템플릿 폴백): {}", chunk.size, e.message)
                return@forEach
            }
            response.path("reasons").forEach { node ->
                val photoId = node.path("photo_id").asText(null)?.toLongOrNull() ?: return@forEach
                val text = node.path("reason").asText("").trim()
                val item = byId[photoId] ?: return@forEach
                if (text.isEmpty()) return@forEach
                val limit = if (item.image != null) ReasonPrompt.MAX_REASON_CHARS else ReasonPrompt.MAX_TEXT_ONLY_CHARS
                if (text.length > limit) {
                    log.info("이유 문장 상한 초과 → 템플릿 ({}, {}자)", photoId, text.length)
                    return@forEach
                }
                out[photoId] = text
            }
        }
        return out
    }

    companion object {
        /** 배치는 사용자가 기다리지 않으므로 스로틀링에 몇 번 더 시도한다. */
        private const val BATCH_RETRIES = 2
    }
}
