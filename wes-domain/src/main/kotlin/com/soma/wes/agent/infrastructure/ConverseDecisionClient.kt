package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import com.soma.wes.agent.service.port.DecisionClient
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.AnyToolChoice
import software.amazon.awssdk.services.bedrockruntime.model.CachePointBlock
import software.amazon.awssdk.services.bedrockruntime.model.CachePointType
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.Tool
import software.amazon.awssdk.services.bedrockruntime.model.ToolChoice
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * 운영 판단기 — Bedrock Converse를 SDK로 직접 부른다. 도구 정의를 주고, 모델이 고른 도구 하나와 인자를 주문서로 옮긴다(LLM 호출 1회).
 *
 * 두 손잡이가 응답 시간과 비용을 정한다(강화 계획 2단계 측정, 2026-10-16, Sonnet 4.6 바꿔 말하기 124문항):
 * - 도구 강제(`toolChoice=any`): 모델이 도구 앞에 글을 쓰지 않는다 — p50 1.76s → 1.22s, 정확도 유지.
 *   Spring AI Bedrock 모듈에는 이 옵션이 없어 SDK를 직접 부른다.
 * - 프롬프트 캐시: 도구 정의·지침·폴더 목록 뒤에 캐시 지점 — 입력의 약 87%가 캐시 읽기, 1,000문항당 비용 −73%.
 *
 * Sonnet 5.5·Opus 5.5는 도구 강제를 400으로 거절한다 — 그 모델에는 [forceTool]을 끈다.
 */
class ConverseDecisionClient(
    private val client: BedrockRuntimeClient,
    private val objectMapper: ObjectMapper,
    private val modelId: String,
    private val forceTool: Boolean,
    private val cache: Boolean,
    /** 생각이 기본으로 켜진 모델(Haiku 5.5 등)에서 끈다 — 분류 한 번에 생각은 지연만 늘린다. */
    private val disableThinking: Boolean = false,
) : DecisionClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val reader = AgentDecisionReader(objectMapper)
    private val tools = toolConfiguration()

    companion object {

        /** 도구 인자만 받는다. 프로토타입 기준 출력은 100토큰 안팎이다. */
        const val MAX_TOKENS = 600
        private const val NANOS_PER_MILLI = 1_000_000
        private val CACHE_POINT: CachePointBlock = CachePointBlock.builder().type(CachePointType.DEFAULT).build()
    }

    override fun decide(question: String, context: AgentContextDto): AgentDecisionDto {
        val startedAt = System.nanoTime()
        val (decision, response) = runCatching { converse(question, context) }.getOrElse { e ->
            log.warn("Bedrock Converse 판단 실패: model={}, error={}", modelId, e.toString())
            throw AgentException(AgentErrorCode.DECISION_UNAVAILABLE)
        }
        val usage = response.usage()
        log.info(
            "Bedrock Converse 판단: {}ms, in={}, out={}, cacheRead={}, decision={}",
            (System.nanoTime() - startedAt) / NANOS_PER_MILLI,
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheReadInputTokens(),
            decision::class.simpleName,
        )
        return decision
    }

    /** 판단과 원 응답을 함께 돌려준다 — 측정 하네스가 토큰 수(캐시 포함)를 남긴다. 실패는 SDK 예외 그대로다. */
    fun converse(question: String, context: AgentContextDto): Pair<AgentDecisionDto, ConverseResponse> {
        val response = client.converse(request(question, context))
        val blocks = response.output().message().content()
        val toolUse = blocks.firstNotNullOfOrNull { it.toolUse() }
        val decision = reader.read(
            toolName = toolUse?.name(),
            arguments = toolUse?.input()?.let { objectMapper.writeValueAsString(toJson(it)) },
            text = blocks.mapNotNull { it.text()?.trim() }.firstOrNull { it.isNotEmpty() },
            context = context,
        )
        return decision to response
    }

    private fun request(question: String, context: AgentContextDto): ConverseRequest {
        val system = buildList {
            add(SystemContentBlock.fromText(AgentDecisionPrompt.stable(context, objectMapper)))
            if (cache) add(SystemContentBlock.fromCachePoint(CACHE_POINT))
            add(SystemContentBlock.fromText(AgentDecisionPrompt.volatile(context, objectMapper)))
        }
        val builder = ConverseRequest.builder()
            .modelId(modelId)
            .system(system)
            .messages(Message.builder().role("user").content(ContentBlock.fromText(question)).build())
            .toolConfig(tools)
            .inferenceConfig { it.maxTokens(MAX_TOKENS) }
        if (disableThinking) {
            builder.additionalModelRequestFields(Document.mapBuilder().putDocument("thinking", Document.mapBuilder().putString("type", "disabled").build()).build())
        }
        return builder.build()
    }

    private fun toolConfiguration(): ToolConfiguration {
        val specs = AgentDecisionTools.definitions.map { definition ->
            Tool.fromToolSpec(
                ToolSpecification.builder()
                    .name(definition.name)
                    .description(definition.description)
                    .inputSchema(ToolInputSchema.fromJson(toDocument(objectMapper.readTree(definition.inputSchema))))
                    .build(),
            )
        }
        val tools = if (cache) specs + Tool.fromCachePoint(CACHE_POINT) else specs
        val builder = ToolConfiguration.builder().tools(tools)
        if (forceTool) builder.toolChoice(ToolChoice.fromAny(AnyToolChoice.builder().build()))
        return builder.build()
    }

    private fun toDocument(node: JsonNode): Document =
        when {
            node.isObject -> Document.fromMap(node.properties().associate { (key, value) -> key to toDocument(value) })
            node.isArray -> Document.fromList(node.values().map { toDocument(it) })
            node.isBoolean -> Document.fromBoolean(node.asBoolean())
            node.isIntegralNumber -> Document.fromNumber(node.asLong())
            node.isNumber -> Document.fromNumber(node.decimalValue())
            node.isNull -> Document.fromNull()
            else -> Document.fromString(node.asString())
        }

    private fun toJson(document: Document): Any? =
        when {
            document.isMap -> document.asMap().mapValues { (_, value) -> toJson(value) }
            document.isList -> document.asList().map { toJson(it) }
            document.isBoolean -> document.asBoolean()
            document.isNumber -> document.asNumber().bigDecimalValue()
            document.isNull -> null
            else -> document.asString()
        }
}
