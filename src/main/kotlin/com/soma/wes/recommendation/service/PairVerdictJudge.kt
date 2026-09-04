package com.soma.wes.recommendation.service

import com.soma.wes.category.support.AiCategoryFolderSetReader
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.service.PreviewImageReader
import com.soma.wes.recommendation.config.LlmProperties
import com.soma.wes.recommendation.domain.AiPairVerdict
import com.soma.wes.recommendation.dto.ComparablePhotoDto
import com.soma.wes.recommendation.dto.LlmJsonRequestDto
import com.soma.wes.recommendation.dto.PairFactsDto
import com.soma.wes.recommendation.dto.PairVerdictDto
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.repository.AiPairVerdictRepository
import com.soma.wes.recommendation.support.ComparePrompt
import com.soma.wes.recommendation.support.PairFactCollector
import com.soma.wes.recommendation.support.TemplateVerdictRule
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 비교샷 판정 — 캐시 → 사실 수집 → 템플릿 판정 → (켜져 있으면) LLM 판정 → 저장.
 * AI repo `compare.verdict.run`의 자리다. 항상 판정 하나를 돌려준다 — LLM 실패·예산 초과는 템플릿이다.
 *
 * `@Transactional`을 클래스에 걸지 않는 것은 의도다. LLM 호출(예산 8초)이 커넥션을 물면 안 되므로
 * 읽기와 저장을 각각 짧은 트랜잭션으로 끊고 그 사이에 LLM을 부른다.
 */
@Service
class PairVerdictJudge(
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val aiPairVerdictRepository: AiPairVerdictRepository,
    private val folderSetReader: AiCategoryFolderSetReader,
    private val previewImageReader: PreviewImageReader,
    private val llm: StructuredLlmClient,
    private val properties: LlmProperties,
    private val transactionTemplate: TransactionTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 모델 id + 프롬프트 세대. 저장된 판정의 세대가 다르면 다시 판정해 덮는다. */
    private val modelVersion: String
        get() = "${properties.modelId}+${ComparePrompt.PROMPT_VERSION}"

    fun judge(selectionId: Long, galleryId: Long, photoA: Long, photoB: Long): PairVerdictDto {
        val prepared = transactionTemplate.execute { prepare(selectionId, galleryId, photoA, photoB) }!!
        if (prepared.cached != null) return prepared.cached

        val decision = decide(prepared)

        return transactionTemplate.execute { save(selectionId, photoA, photoB, prepared, decision) }!!
    }

    private fun prepare(selectionId: Long, galleryId: Long, photoA: Long, photoB: Long): Prepared {
        val existing = aiPairVerdictRepository.findByPair(selectionId, photoA, photoB)
        if (existing != null && existing.modelVersion == modelVersion) {
            return Prepared(cached = existing.toDto(cached = true))
        }

        // 행은 업로드 확정 때 빈 값으로 먼저 생긴다 — 분석 컬럼이 채워진 행만 재료가 된다.
        val analyses = photoAnalysisRepository.findAllByPhotoIdIn(listOf(photoA, photoB))
            .filter { it.isAnalyzed }
            .associateBy { it.photoId }
        val a = analyses[photoA]?.let(ComparablePhotoDto::from)
            ?: throw RecommendationException(RecommendationErrorCode.COMPARE_NOT_ANALYZED)
        val b = analyses[photoB]?.let(ComparablePhotoDto::from)
            ?: throw RecommendationException(RecommendationErrorCode.COMPARE_NOT_ANALYZED)

        val folders = folderSetReader.folderNamesByPhotoId(listOf(photoA, photoB))
        val selected = photoSelectionItemRepository.findAllBySelectionId(selectionId).mapTo(mutableSetOf()) { it.photoId }
        val previews = photoRepository.findAllByGalleryIdAndIdIn(galleryId, listOf(photoA, photoB))
            .associate { it.requiredId to it.previewKey }

        return Prepared(
            a = a,
            b = b,
            facts = PairFactCollector.collect(a, b, folders[photoA], folders[photoB], selected),
            previewKeyA = previews[photoA],
            previewKeyB = previews[photoB],
        )
    }

    /** 템플릿을 먼저 정해 두고 LLM이 계약 안의 답을 주면 그것으로 바꾼다. 실패의 종류를 가리지 않는다. */
    private fun decide(prepared: Prepared): Decision {
        val template = TemplateVerdictRule.decide(prepared.a, prepared.b)
        val fallback = Decision(template.chosen, "slight", template.reason, SOURCE_TEMPLATE)
        if (!llm.isEnabled) return fallback

        return try {
            val keyA = prepared.previewKeyA ?: throw IllegalStateException("미리보기가 없다: ${prepared.a.photoId}")
            val keyB = prepared.previewKeyB ?: throw IllegalStateException("미리보기가 없다: ${prepared.b.photoId}")
            val parts = ComparePrompt.userParts(
                imageA = previewImageReader.readJpeg(keyA, properties.imageLongEdge),
                imageB = previewImageReader.readJpeg(keyB, properties.imageLongEdge),
                sentences = prepared.facts.sentences,
            )
            val out = llm.completeJson(
                LlmJsonRequestDto(
                    system = ComparePrompt.SYSTEM,
                    parts = parts,
                    schema = ComparePrompt.SCHEMA,
                    maxTokens = properties.compareMaxTokens,
                    timeout = properties.compareTimeout,
                    maxRetries = 0,
                ),
            )
            val chosen = out.path("chosen").asText(null)
            val confidence = out.path("confidence").asText(null)
            val reason = out.path("reason").asText("").trim()
            if (chosen in SIDES && confidence in CONFIDENCES && reason.isNotEmpty()) {
                Decision(
                    chosen = if (chosen == "a") TemplateVerdictRule.Side.A else TemplateVerdictRule.Side.B,
                    confidence = confidence,
                    reason = reason.take(ComparePrompt.MAX_REASON_CHARS),
                    source = SOURCE_LLM,
                )
            } else {
                log.warn("compare LLM 응답이 계약을 벗어남 ({}) — 템플릿 판정", out)
                fallback
            }
        } catch (e: Exception) {
            log.warn("compare LLM 실패 → 템플릿 판정: {}", e.message)
            fallback
        }
    }

    private fun save(selectionId: Long, photoA: Long, photoB: Long, prepared: Prepared, decision: Decision): PairVerdictDto {
        val chosenPhotoId = if (decision.chosen == TemplateVerdictRule.Side.A) photoA else photoB
        val existing = aiPairVerdictRepository.findByPair(selectionId, photoA, photoB)
        val verdict = if (existing != null) {
            existing.replaceWith(chosenPhotoId, decision.confidence, decision.reason, prepared.facts.facts, modelVersion, decision.source)
            existing
        } else {
            try {
                aiPairVerdictRepository.saveAndFlush(
                    AiPairVerdict(
                        selectionId = selectionId,
                        photoA = photoA,
                        photoB = photoB,
                        chosenPhotoId = chosenPhotoId,
                        confidence = decision.confidence,
                        reason = decision.reason,
                        facts = prepared.facts.facts,
                        modelVersion = modelVersion,
                        source = decision.source,
                    ),
                )
            } catch (e: DataIntegrityViolationException) {
                // 같은 쌍을 동시에 판정한 다른 요청이 먼저 저장했다 — 그쪽 판정이 정본이다.
                return aiPairVerdictRepository.findByPair(selectionId, photoA, photoB)!!.toDto(cached = true)
            }
        }
        return verdict.toDto(cached = false)
    }

    private fun AiPairVerdict.toDto(cached: Boolean) = PairVerdictDto(
        chosenPhotoId = chosenPhotoId,
        confidence = confidence,
        reason = reason,
        source = source,
        cached = cached,
    )

    private class Prepared(
        val cached: PairVerdictDto? = null,
        val a: ComparablePhotoDto = EMPTY,
        val b: ComparablePhotoDto = EMPTY,
        val facts: PairFactsDto = PairFactsDto(emptyList(), emptyMap()),
        val previewKeyA: String? = null,
        val previewKeyB: String? = null,
    )

    private data class Decision(
        val chosen: TemplateVerdictRule.Side,
        val confidence: String,
        val reason: String,
        val source: String,
    )

    companion object {
        const val SOURCE_LLM = "llm"
        const val SOURCE_TEMPLATE = "template"
        private val SIDES = setOf("a", "b")
        private val CONFIDENCES = setOf("clear", "slight")
        private val EMPTY = ComparablePhotoDto(0, 0.0, 0.0, null, null, null, null, "unknown")
    }
}
