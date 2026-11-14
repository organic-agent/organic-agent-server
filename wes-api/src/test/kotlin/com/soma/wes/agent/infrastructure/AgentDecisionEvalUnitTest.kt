package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.exception.AgentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.Duration

/**
 * 질문 세트 측정(강화 계획 1단계). AI repo 질문 세트(원문 62 · 바꿔 말하기 124)를 판단기로 판단해 프로토타입과 같은 모양의
 * 예측을 `out/wes-predictions-<세트>-<라벨>.json`에 쓴다. 채점·비용·비교 표는 `scripts/dev-tools/agent-eval.sh`가 잇는다.
 *
 *   AGENT_EVAL=1 AGENT_EVAL_DIR=<AI repo>/docs/experiments/photo-agent-eval-set-2026-10-09 \
 *   AGENT_EVAL_LABEL=sonnet-4-6 AGENT_EVAL_RUNS=3 \
 *     ./gradlew :wes-api:test --rerun --tests '*AgentDecisionEvalUnitTest*'
 *
 * 운영 판단기 구현([ConverseDecisionClient])으로 실제 Bedrock을 부른다 — 돈이 든다(102문항 1회 ≈ $0.4).
 * bedrock 손잡이는 운영 기본값(도구 강제·캐시 켬)에서 시작한다: AGENT_EVAL_FORCE_TOOL=0·AGENT_EVAL_CACHE=0으로 끄고, AGENT_EVAL_NO_THINKING=1로 생각을 끈다.
 * 문항마다 지연(ms)·토큰을 남기고, 프로세스의 첫 호출은 콜드(`cold`)로 표시해 채점기가 지연 백분위에서 뺀다.
 * 판단기가 받는 문맥은 그 폴더의 `out/catalog.json`(갤러리 1, dataset2 7,189장)과 고른 사진 30장(골든 세트)이다.
 */
@EnabledIfEnvironmentVariable(named = "AGENT_EVAL", matches = "1")
class AgentDecisionEvalUnitTest {

    private val mapper = JsonMapper.builder().build()
    private val dir = File(checkNotNull(System.getenv("AGENT_EVAL_DIR")) { "AGENT_EVAL_DIR 에 질문 세트 폴더를 준다" })
    private val model = System.getenv("AGENT_EVAL_MODEL") ?: DEFAULT_MODEL
    private val label = System.getenv("AGENT_EVAL_LABEL") ?: "bedrock-$model"
    private val usage = UsageRecorder()
    private val converseClient = converseClient()
    private var calls = 0

    companion object {

        private const val DEFAULT_MODEL = "global.anthropic.claude-sonnet-4-6"
        private const val SELECTED_COUNT = 30
        private const val NANOS_PER_MILLI = 1_000_000
        /** 세트 파일. 세트 v2(#348 PR-2)는 AGENT_EVAL_SETS=questions_v2 로 고른다. */
        private val QUESTION_FILES = (System.getenv("AGENT_EVAL_SETS") ?: "questions,questions_paraphrase").split(",").map { "$it.jsonl" }
        private val TIMEOUT: Duration = Duration.ofSeconds(30)
    }

    @Test
    fun `질문 세트를 판단해 예측을 쓴다`() {
        // given
        val context = context(mapper.readTree(File(dir, "out/catalog.json")))
        val runs = System.getenv("AGENT_EVAL_RUNS")?.toInt() ?: 1

        // when & then — 판단 결과는 파일로 남고 채점은 scripts/dev-tools/agent-eval.sh 가 한다
        for (file in QUESTION_FILES) {
            val questions = File(dir, file).readLines().filter { it.isNotBlank() }.map { mapper.readTree(it) }
            val rows = (0 until runs).flatMap { run -> questions.map { question -> predict(question, context, run) } }
            val out = File(dir, "out/wes-predictions-${file.removeSuffix(".jsonl")}-$label.json")
            val meta = mapOf("label" to label, "engine" to "bedrock", "model" to model, "runs" to runs)
            mapper.writerWithDefaultPrettyPrinter().writeValue(out, meta + mapOf("rows" to rows))
            println("[eval] $file → ${out.path} (${rows.size} rows)")
        }
    }

    private fun predict(question: JsonNode, context: AgentContextDto, run: Int): Map<String, Any?> {
        usage.clear()
        val startedAt = System.nanoTime()
        val decision = try {
            decide(question.path("text").asString(), context)
        } catch (e: AgentException) {
            AgentDecisionDto.Clarify("판단 실패: ${e.message}")
        } catch (e: SdkException) {
            AgentDecisionDto.Clarify("판단 실패: ${e.message}")
        }
        val millis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        val id = question.path("id").asString()
        val cold = calls++ == 0
        println("[eval] #$run $id ${decision::class.simpleName} ${millis}ms in=${usage.input} out=${usage.output}")
        return mapOf(
            "id" to id,
            "run" to run,
            "ms" to millis,
            "cold" to cold,
            "in" to usage.input,
            "out" to usage.output,
            "cache_read" to usage.cacheRead,
            "pred" to prototypePrediction(decision),
        )
    }

    /** 원 응답에서 토큰 수(캐시 읽기 포함)를 꺼낸다. */
    private fun decide(question: String, context: AgentContextDto): AgentDecisionDto {
        val (decision, response) = converseClient.converse(question, context)
        usage.input = response.usage().inputTokens()
        usage.output = response.usage().outputTokens()
        usage.cacheRead = response.usage().cacheReadInputTokens()
        return decision
    }

    /** 실제 Bedrock(서울, global 프로필). 운영 판단기와 같은 구현이다. */
    private fun converseClient(): ConverseDecisionClient {
        val bedrock = BedrockRuntimeClient.builder()
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .region(Region.AP_NORTHEAST_2)
            .overrideConfiguration { it.apiCallTimeout(TIMEOUT) }
            .build()
        return ConverseDecisionClient(
            client = bedrock,
            objectMapper = mapper,
            modelId = model,
            forceTool = System.getenv("AGENT_EVAL_FORCE_TOOL") != "0",
            cache = System.getenv("AGENT_EVAL_CACHE") != "0",
            disableThinking = System.getenv("AGENT_EVAL_NO_THINKING") == "1",
        )
    }

    /** 프로토타입(`agent_eval.py` LLMRouter)의 예측 모양 — 채점기가 이것을 읽는다. */
    private fun prototypePrediction(decision: AgentDecisionDto): Map<String, Any?> =
        when (decision) {
            is AgentDecisionDto.FindFolder -> mapOf(
                "intent" to "find_folder",
                "targets" to decision.targets.map { target -> target.detailFolderId?.let { "d$it" } ?: "c${target.conceptFolderId}" },
            )
            is AgentDecisionDto.FilterPhotos -> mapOf(
                "intent" to "filter_photos",
                "filter" to prototypeFilter(decision.filter),
                "unsupported" to decision.unsupported.map { it.name.lowercase() },
            )
            is AgentDecisionDto.Stats -> mapOf(
                "intent" to "stats",
                "metric" to decision.metric.name.lowercase(),
                "scope" to decision.scope.name.lowercase(),
                "filter" to prototypeFilter(decision.filter),
                "unsupported" to decision.unsupported.map { it.name.lowercase() },
            )
            is AgentDecisionDto.PlanAction -> mapOf(
                "intent" to "action",
                "op" to decision.op.name.lowercase(),
                "filter" to prototypeFilter(decision.filter),
                "target" to decision.targetDetailFolderId,
                "unsupported" to decision.unsupported.map { it.name.lowercase() },
            )
            is AgentDecisionDto.PlanRename -> mapOf(
                "intent" to "rename",
                "folder" to (decision.target.detailFolderId?.let { "d$it" } ?: "c${decision.target.conceptFolderId}"),
                "name" to decision.name,
            )
            is AgentDecisionDto.Check -> mapOf("intent" to "check", "focus" to decision.focus?.code)
            is AgentDecisionDto.Clarify -> mapOf("intent" to "clarify", "question" to decision.question)
            is AgentDecisionDto.Explain -> mapOf("intent" to "explain", "topic" to decision.topic.code)
        }

    private fun prototypeFilter(filter: PhotoFilter): Map<String, Any?> =
        buildMap {
            if (filter.conceptFolderIds.isNotEmpty()) put("concepts", filter.conceptFolderIds)
            if (filter.detailFolderIds.isNotEmpty()) put("details", filter.detailFolderIds)
            if (filter.subjects.isNotEmpty()) put("subjects", filter.subjects.map { it.analysisValue })
            filter.selected?.let { put("selected", it) }
            filter.orientation?.let { put("orientation", it.name.lowercase()) }
            if (filter.burstRepresentativeOnly) put("burst_rep", true)
            filter.likedByGuests?.let { put("liked_by_guests", it) }
            filter.hasComments?.let { put("has_comments", it) }
            filter.minRating?.let { put("min_rating", it) }
            filter.hasMemo?.let { put("has_memo", it) }
            filter.maxRating?.let { put("max_rating", it) }
            filter.addedBy?.let { put("added_by", it.name.lowercase()) }
            filter.ratedBy?.let { put("rated_by", it.name.lowercase()) }
            filter.memoBy?.let { put("memo_by", it.name.lowercase()) }
            filter.commentedBy?.let { put("commented_by", it.name.lowercase()) }
        }

    private fun context(catalog: JsonNode): AgentContextDto {
        val detailsByConceptId = catalog.path("details").groupBy { it.path("concept_id").asLong() }
        return AgentContextDto(
            concepts = catalog.path("concepts").values().map { concept ->
                AgentContextDto.Concept(
                    id = concept.path("id").asLong(),
                    name = concept.path("name").asString(),
                    shotOrder = concept.path("shot_order").asInt(),
                    timeRange = concept.path("time").asString(),
                    photoCount = concept.path("photos").asInt(),
                    details = detailsByConceptId[concept.path("id").asLong()].orEmpty().map { detail ->
                        AgentContextDto.Detail(
                            id = detail.path("id").asLong(),
                            name = detail.path("name").asString(),
                            photoCount = detail.path("photos").asInt(),
                        )
                    },
                )
            },
            selectedCount = SELECTED_COUNT,
            maxSelectablePhotoCount = null,
            screen = null,
        )
    }

    /** 판단 한 번의 토큰 수. */
    private class UsageRecorder {

        var input: Int? = null
        var output: Int? = null
        var cacheRead: Int? = null

        fun clear() {
            input = null
            output = null
            cacheRead = null
        }
    }
}
