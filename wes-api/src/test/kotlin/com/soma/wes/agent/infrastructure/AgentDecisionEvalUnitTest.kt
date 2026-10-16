package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.exception.AgentException
import com.soma.wes.agent.service.port.DecisionClient
import com.soma.wes.global.config.LlmProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.http.HttpHeaders
import org.springframework.http.client.BufferingClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
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
 * 예측을 `out/wes-predictions-<세트>-<라벨>.json`에 쓴다. 채점·비용·비교 표는 `scripts/agent-eval/run.sh`가 잇는다.
 *
 *   AGENT_EVAL=1 AGENT_EVAL_DIR=<AI repo>/docs/experiments/photo-agent-eval-set-2026-10-09 \
 *   AGENT_EVAL_ENGINE=openai AGENT_EVAL_MODEL=codex-terra AGENT_EVAL_LABEL=codex-terra AGENT_EVAL_RUNS=3 \
 *     ./gradlew :wes-api:test --rerun --tests '*AgentDecisionEvalUnitTest*'
 *
 * 엔진은 판단기 구현을 그대로 쓴다 — bedrock(운영 [ConverseDecisionClient], 돈이 든다)·openai(`scripts/llm` 게이트웨이, 구독 계정).
 * bedrock 손잡이는 운영 기본값(도구 강제·캐시 켬)에서 시작한다: AGENT_EVAL_FORCE_TOOL=0·AGENT_EVAL_CACHE=0으로 끄고, AGENT_EVAL_NO_THINKING=1로 생각을 끈다.
 * 문항마다 지연(ms)·토큰을 남기고, 프로세스의 첫 호출은 콜드(`cold`)로 표시해 채점기가 지연 백분위에서 뺀다.
 * 판단기가 받는 문맥은 그 폴더의 `out/catalog.json`(갤러리 1, dataset2 7,189장)과 고른 사진 30장(골든 세트)이다.
 */
@EnabledIfEnvironmentVariable(named = "AGENT_EVAL", matches = "1")
class AgentDecisionEvalUnitTest {

    private val mapper = JsonMapper.builder().build()
    private val dir = File(checkNotNull(System.getenv("AGENT_EVAL_DIR")) { "AGENT_EVAL_DIR 에 질문 세트 폴더를 준다" })
    private val engine = System.getenv("AGENT_EVAL_ENGINE") ?: ENGINE_BEDROCK
    private val model = System.getenv("AGENT_EVAL_MODEL") ?: defaultModel(engine)
    private val label = System.getenv("AGENT_EVAL_LABEL") ?: "$engine-$model"
    private val usage = UsageRecorder()
    private val converseClient = converseClient()
    private val client = decisionClient()
    private var calls = 0

    companion object {

        private const val ENGINE_BEDROCK = "bedrock"
        private const val ENGINE_OPENAI = "openai"
        private const val SELECTED_COUNT = 30
        private const val NANOS_PER_MILLI = 1_000_000
        private val QUESTION_FILES = listOf("questions.jsonl", "questions_paraphrase.jsonl")
        private val TIMEOUT: Duration = Duration.ofSeconds(30)

        private fun defaultModel(engine: String): String =
            if (engine == ENGINE_OPENAI) "codex-terra" else "global.anthropic.claude-sonnet-4-6"
    }

    @Test
    fun `질문 세트를 판단해 예측을 쓴다`() {
        val context = context(mapper.readTree(File(dir, "out/catalog.json")))
        val runs = System.getenv("AGENT_EVAL_RUNS")?.toInt() ?: 1

        for (file in QUESTION_FILES) {
            val questions = File(dir, file).readLines().filter { it.isNotBlank() }.map { mapper.readTree(it) }
            val rows = (0 until runs).flatMap { run -> questions.map { question -> predict(question, context, run) } }
            val out = File(dir, "out/wes-predictions-${file.removeSuffix(".jsonl")}-$label.json")
            val meta = mapOf("label" to label, "engine" to engine, "model" to model, "runs" to runs)
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

    /** bedrock은 원 응답에서 토큰 수(캐시 읽기 포함)를 꺼낸다. openai는 클라이언트를 가로챈 [usage]가 채운다. */
    private fun decide(question: String, context: AgentContextDto): AgentDecisionDto {
        val converse = converseClient ?: return client.decide(question, context)
        val (decision, response) = converse.converse(question, context)
        usage.input = response.usage().inputTokens()
        usage.output = response.usage().outputTokens()
        usage.cacheRead = response.usage().cacheReadInputTokens()
        return decision
    }

    /** 실제 Bedrock(서울, global 프로필). 운영 판단기와 같은 구현이다. */
    private fun converseClient(): ConverseDecisionClient? {
        if (engine != ENGINE_BEDROCK) return null
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

    private fun decisionClient(): DecisionClient =
        when (engine) {
            ENGINE_BEDROCK -> checkNotNull(converseClient)
            ENGINE_OPENAI -> OpenAiDecisionClient(
                restClient = recordingOpenAi(),
                objectMapper = mapper,
                model = model,
                reasoningEffort = System.getenv("AGENT_EVAL_REASONING_EFFORT") ?: LlmProperties.OpenAi().reasoningEffort,
            )
            else -> error("AGENT_EVAL_ENGINE 은 $ENGINE_BEDROCK 또는 $ENGINE_OPENAI 다: $engine")
        }

    /** `scripts/llm` 게이트웨이. 키는 `WES_LLM_API_KEY`(LiteLLM 관리자 키), 응답 본문의 usage를 [usage]에 남긴다. */
    private fun recordingOpenAi(): RestClient {
        val defaults = LlmProperties.OpenAi()
        val apiKey = checkNotNull(System.getenv("WES_LLM_API_KEY")) { "WES_LLM_API_KEY 에 scripts/llm/.env 의 LITELLM_MASTER_KEY 를 준다" }
        val requestFactory = BufferingClientHttpRequestFactory(JdkClientHttpRequestFactory().apply { setReadTimeout(TIMEOUT) })
        return RestClient.builder()
            .baseUrl(System.getenv("WES_LLM_BASE_URL") ?: defaults.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
            .requestFactory(requestFactory)
            .requestInterceptor { request, body, execution ->
                execution.execute(request, body).also { response ->
                    val root = mapper.readTree(response.body.readAllBytes())
                    usage.input = root.path("usage").path("prompt_tokens").asInt(0)
                    usage.output = root.path("usage").path("completion_tokens").asInt(0)
                    usage.cacheRead = root.path("usage").path("prompt_tokens_details").path("cached_tokens").asInt(0)
                }
            }
            .build()
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
            is AgentDecisionDto.Clarify -> mapOf("intent" to "clarify", "question" to decision.question)
        }

    private fun prototypeFilter(filter: PhotoFilter): Map<String, Any?> =
        buildMap {
            if (filter.conceptFolderIds.isNotEmpty()) put("concepts", filter.conceptFolderIds)
            if (filter.detailFolderIds.isNotEmpty()) put("details", filter.detailFolderIds)
            if (filter.subjects.isNotEmpty()) put("subjects", filter.subjects.map { it.analysisValue })
            filter.selected?.let { put("selected", it) }
            filter.orientation?.let { put("orientation", it.name.lowercase()) }
            if (filter.burstRepresentativeOnly) put("burst_rep", true)
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

    /** 판단 한 번의 토큰 수. 판단기 구현은 토큰을 돌려주지 않아 클라이언트 쪽에서 가로챈다. */
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
