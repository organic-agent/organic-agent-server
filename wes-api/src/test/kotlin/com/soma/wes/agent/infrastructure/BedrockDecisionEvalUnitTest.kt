package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.exception.AgentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * 질문 세트 재측정(계획 문서 7단계 C). AI repo 질문 세트(원문 62 · 바꿔 말하기 124)를 wes 판단기로 판단해
 * 프로토타입과 같은 모양의 예측을 `out/wes-predictions-<세트>.json`에 쓴다. 채점은 그 폴더의 `grade_wes.py`가
 * 프로토타입 채점기(`agent_eval.grade`)로 한다 — 정답 사진 집합을 DB에서 다시 세야 해서다.
 *
 *   BEDROCK_EVAL=1 AGENT_EVAL_DIR=<AI repo>/docs/experiments/photo-agent-eval-set-2026-10-09 \
 *     ./gradlew :wes-api:test --rerun --tests '*BedrockDecisionEvalUnitTest*'
 *
 * 판단기가 받는 문맥은 그 폴더의 `out/catalog.json`(갤러리 1, dataset2 7,189장)과 고른 사진 30장(골든 세트)이다.
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_EVAL", matches = "1")
class BedrockDecisionEvalUnitTest {

    private val mapper = JsonMapper.builder().build()
    private val dir = File(checkNotNull(System.getenv("AGENT_EVAL_DIR")) { "AGENT_EVAL_DIR 에 질문 세트 폴더를 준다" })
    private val client = BedrockDecisionClientUnitTest.bedrockDecisionClient()

    companion object {

        private const val SELECTED_COUNT = 30
        private const val NANOS_PER_MILLI = 1_000_000
        private val QUESTION_FILES = listOf("questions.jsonl", "questions_paraphrase.jsonl")
    }

    @Test
    fun `질문 세트를 판단해 예측을 쓴다`() {
        val context = context(mapper.readTree(File(dir, "out/catalog.json")))
        val runs = System.getenv("AGENT_EVAL_RUNS")?.toInt() ?: 1

        for (file in QUESTION_FILES) {
            val questions = File(dir, file).readLines().filter { it.isNotBlank() }.map { mapper.readTree(it) }
            val rows = (0 until runs).flatMap { run -> questions.map { question -> predict(question, context, run) } }
            val out = File(dir, "out/wes-predictions-${file.removeSuffix(".jsonl")}.json")
            mapper.writerWithDefaultPrettyPrinter().writeValue(out, mapOf("runs" to runs, "rows" to rows))
            println("[eval] $file → ${out.path} (${rows.size} rows)")
        }
    }

    private fun predict(question: JsonNode, context: AgentContextDto, run: Int): Map<String, Any?> {
        val startedAt = System.nanoTime()
        val decision = try {
            client.decide(question.path("text").asString(), context)
        } catch (e: AgentException) {
            AgentDecisionDto.Clarify("판단 실패: ${e.message}")
        }
        val millis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        val id = question.path("id").asString()
        println("[eval] #$run $id ${decision::class.simpleName} ${millis}ms")
        return mapOf("id" to id, "run" to run, "ms" to millis, "pred" to prototypePrediction(decision))
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
}
