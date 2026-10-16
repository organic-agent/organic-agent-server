package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.domain.PhotoOrientation
import com.soma.wes.agent.domain.StatsMetric
import com.soma.wes.agent.domain.StatsScope
import com.soma.wes.agent.domain.UnsupportedCondition
import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import com.soma.wes.agent.service.port.DecisionClient
import org.slf4j.LoggerFactory
import org.springframework.ai.bedrock.converse.BedrockChatOptions
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Bedrock(Converse) 판단기. 도구 다섯 개의 정의를 주고, 모델이 고른 도구 하나와 인자를 주문서로 옮긴다 — LLM 호출은 한 번이다.
 *
 * 모델이 도구 없이 글로만 답하면 그 글을 되묻기로 쓴다. Spring AI의 Bedrock 모듈에는 강제 도구 선택(tool_choice)이 없어서다.
 * 모델이 이 갤러리에 없는 폴더 id를 지어내면 그 id는 버린다(폴더 찾기). 조건 속의 없는 id는 아무 사진에도 걸리지 않아 그대로 둔다 —
 * 버리면 조건이 넓어져 "전부 옮기기"가 될 수 있다.
 */
class BedrockDecisionClient(
    private val chatModel: ChatModel,
    private val objectMapper: ObjectMapper,
    private val modelId: String,
) : DecisionClient {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {

        /** 도구 인자만 받는다. 프로토타입 기준 출력은 100토큰 안팎이다. */
        const val MAX_TOKENS = 600
        const val FALLBACK_QUESTION = "무엇을 도와드릴까요? 폴더 찾기·사진 거르기·세기·선택 넣고 빼기·폴더 옮기기를 할 수 있어요."

        /** 시스템 프롬프트의 폴더 id 표기 — 'c12'는 컨셉, 'd31'은 세부 폴더. */
        private const val CONCEPT_PREFIX = "c"
        private const val DETAIL_PREFIX = "d"
        private const val NANOS_PER_MILLI = 1_000_000
    }

    override fun decide(question: String, context: AgentContextDto): AgentDecisionDto {
        val prompt = Prompt(
            listOf(SystemMessage(AgentDecisionPrompt.system(context, objectMapper)), UserMessage(question)),
            BedrockChatOptions.builder()
                .model(modelId)
                .maxTokens(MAX_TOKENS)
                .toolCallbacks(AgentDecisionTools.callbacks)
                .build(),
        )

        val startedAt = System.nanoTime()
        val response = runCatching { chatModel.call(prompt) }.getOrElse { e ->
            log.warn("Bedrock 판단 실패: model={}, error={}", modelId, e.toString())
            throw AgentException(AgentErrorCode.DECISION_UNAVAILABLE)
        }
        // Converse는 글 블록과 도구 블록을 따로 주고, Spring AI는 블록마다 결과(Generation)를 하나씩 만든다 — 첫 결과만 보면 도구를 놓친다.
        val outputs = response.results.map { it.output }
        val toolCall = outputs.firstNotNullOfOrNull { it.toolCalls.firstOrNull() }
        val usage = response.metadata.usage
        log.info(
            "Bedrock 판단: {}ms, in={}, out={}, tool={}",
            (System.nanoTime() - startedAt) / NANOS_PER_MILLI,
            usage.promptTokens,
            usage.completionTokens,
            toolCall?.name(),
        )

        return toDecision(toolCall, outputs, context)
    }

    private fun toDecision(
        toolCall: AssistantMessage.ToolCall?,
        outputs: List<AssistantMessage>,
        context: AgentContextDto,
    ): AgentDecisionDto {
        if (toolCall == null) {
            val text = outputs.mapNotNull { it.text?.trim() }.firstOrNull { it.isNotEmpty() }
            return AgentDecisionDto.Clarify(text ?: FALLBACK_QUESTION)
        }
        val input = objectMapper.readTree(toolCall.arguments().ifBlank { "{}" })

        return when (toolCall.name()) {
            AgentDecisionTools.FIND_FOLDER -> AgentDecisionDto.FindFolder(targets = folderTargets(input.path("targets"), context))
            AgentDecisionTools.FILTER_PHOTOS -> AgentDecisionDto.FilterPhotos(
                filter = filter(input.path("filter")),
                unsupported = unsupported(input.path("unsupported")),
            )
            AgentDecisionTools.STATS -> AgentDecisionDto.Stats(
                metric = enumOf<StatsMetric>(input.path("metric")) ?: StatsMetric.COUNT,
                scope = enumOf<StatsScope>(input.path("scope")) ?: StatsScope.SELECTED,
                filter = filter(input.path("filter")),
                unsupported = unsupported(input.path("unsupported")),
            )
            AgentDecisionTools.PLAN_ACTION -> planAction(input)
            AgentDecisionTools.ASK_CLARIFICATION -> AgentDecisionDto.Clarify(
                question = input.path("question").asString("").trim().ifEmpty { FALLBACK_QUESTION },
            )
            else -> AgentDecisionDto.Clarify(FALLBACK_QUESTION)
        }
    }

    /** 'c12'는 컨셉, 'd31'은 세부 폴더. 세부 폴더는 속한 컨셉을 찾아 함께 적는다. */
    private fun folderTargets(node: JsonNode, context: AgentContextDto): List<AgentDecisionDto.FolderTarget> {
        val conceptIdByDetailId = context.concepts.flatMap { concept -> concept.details.map { it.id to concept.id } }.toMap()
        val conceptIds = context.concepts.map { it.id }.toSet()
        return node.mapNotNull { target ->
            val ref = target.asString("")
            val id = ref.drop(1).toLongOrNull()
            when {
                id == null -> null
                ref.startsWith(CONCEPT_PREFIX) -> AgentDecisionDto.FolderTarget(conceptFolderId = id).takeIf { id in conceptIds }
                ref.startsWith(DETAIL_PREFIX) -> conceptIdByDetailId[id]?.let { conceptId ->
                    AgentDecisionDto.FolderTarget(conceptFolderId = conceptId, detailFolderId = id)
                }
                else -> null
            }
        }
    }

    private fun planAction(input: JsonNode): AgentDecisionDto {
        val op = enumOf<AgentActionOp>(input.path("op")) ?: return AgentDecisionDto.Clarify(FALLBACK_QUESTION)
        val target = input.path("target").takeIf { it.isIntegralNumber }?.asLong()
        return AgentDecisionDto.PlanAction(
            op = op,
            filter = filter(input.path("filter")),
            targetDetailFolderId = target.takeIf { op == AgentActionOp.MOVE },
            unsupported = unsupported(input.path("unsupported")),
        )
    }

    private fun filter(node: JsonNode): PhotoFilter =
        PhotoFilter(
            conceptFolderIds = node.path("concepts").filter { it.isIntegralNumber }.map { it.asLong() },
            detailFolderIds = node.path("details").filter { it.isIntegralNumber }.map { it.asLong() },
            subjects = node.path("subjects").mapNotNull { AgentSubject.fromAnalysis(it.asString("")) },
            selected = node.path("selected").takeIf { it.isBoolean }?.asBoolean(),
            orientation = enumOf<PhotoOrientation>(node.path("orientation")),
            burstRepresentativeOnly = node.path("burstRep").asBoolean(false),
        )

    private fun unsupported(node: JsonNode): List<UnsupportedCondition> =
        node.mapNotNull { enumOf<UnsupportedCondition>(it) }.distinct()

    /** 도구 인자는 소문자(`select_add`), enum은 대문자다. 모르는 값은 null — 모델이 지어낸 값으로 실패하지 않는다. */
    private inline fun <reified E : Enum<E>> enumOf(node: JsonNode): E? {
        val value = node.asString("")
        return enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
