package com.soma.wes.recommendation.service

import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.recommendation.dto.request.ComparePhotosRequest
import com.soma.wes.recommendation.dto.response.PairVerdictResponse
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.repository.PhotoSelectionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 비교샷 — 두 사진 중 AI가 하나를 고르고 근거를 말한다.
 *
 * 이 서버의 첫 동기 AI 유스케이스다. 검증과 셀렉 확보까지가 여기 일이고, 판정(사실 수집 → 템플릿 →
 * LLM, 예산 초과 시 템플릿 폴백)과 저장(`ai_pair_verdicts`, 순서 무관 캐시)은 [PairVerdictJudge]가 한다.
 *
 * 메서드에 `@Transactional`이 없는 것은 의도다 — 5초 안팎의 LLM 호출이 커넥션을 물면 안 된다.
 * 조회·셀렉 생성은 각자 자체 트랜잭션으로 끝나고, 판정기는 자기 트랜잭션을 짧게 끊어 쓴다.
 */
@Service
class AiCompareService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val pairVerdictJudge: PairVerdictJudge,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 셀렉의 주인인 부부만 — 판정은 부부의 선택을 돕는 기능이고 호출마다 비용이 든다.
     * 아직 셀렉 행이 없으면 여기서 만든다(판정이 셀렉 단위로 저장·캐시되기 때문이다).
     * 제출된 앨범에도 허용한다 — 비교는 앨범을 바꾸지 않는 조회성 도움이다.
     */
    fun compare(galleryId: Long, userId: Long, request: ComparePhotosRequest): PairVerdictResponse {
        galleryAccessPolicy.requireSelectionEditor(galleryId, userId)

        if (request.photoA == request.photoB) {
            throw RecommendationException(RecommendationErrorCode.COMPARE_SAME_PHOTO)
        }
        val photoIds = listOf(request.photoA, request.photoB)
        validateComparable(galleryId, photoIds)

        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: photoSelectionRepository.save(PhotoSelection(galleryId = galleryId))

        val verdict = pairVerdictJudge.judge(selection.requiredId, galleryId, request.photoA, request.photoB)

        log.info(
            "비교샷 판정: galleryId={}, selectionId={}, ({}, {}) → {} [{}{}]",
            galleryId, selection.requiredId, request.photoA, request.photoB,
            verdict.chosenPhotoId, verdict.source, if (verdict.cached) ", cached" else "",
        )

        return PairVerdictResponse.from(verdict)
    }

    /**
     * 갤러리 스코프의 살아 있는 사진 두 장이어야 하고, 둘 다 분석이 끝나 있어야 한다.
     * 판정 재료가 분석 컬럼이다 — 판정기 안에서도 막히지만, 셀렉을 만들기 전에 여기서 거절한다.
     */
    private fun validateComparable(galleryId: Long, photoIds: List<Long>) {
        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, photoIds)
        if (photos.size != photoIds.size) {
            throw RecommendationException(RecommendationErrorCode.COMPARE_PHOTO_NOT_FOUND)
        }

        val analyzed = photoAnalysisRepository.findAllByPhotoIdIn(photoIds).count { it.isAnalyzed }
        if (analyzed != photoIds.size) {
            throw RecommendationException(RecommendationErrorCode.COMPARE_NOT_ANALYZED)
        }
    }
}
