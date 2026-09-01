package com.soma.wes.recommendation.service

import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.recommendation.domain.AiAnalysisJob
import com.soma.wes.recommendation.domain.AiAnalysisMode
import com.soma.wes.recommendation.domain.AiJobStatus
import com.soma.wes.recommendation.dto.response.AiAnalysisJobResponse
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.repository.AiAnalysisJobRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 갤러리 전수 분석(태그·점수·클러스터)을 요청한다.
 *
 * 분석 자체는 이 서버가 하지 않는다 — 임베딩과 같은 이유다(모델 가중치, GPU). 이 서버는
 * `ai_analysis_jobs`에 PENDING 행을 넣는 것까지만 하고, 분석 배치가 그 행을 집어가 `photo_analysis`를
 * 채운다. 배치를 깨우는 일(EC2 기동·Lambda 호출)은 이 단계에서 없다 — 배치가 PENDING을 폴링한다.
 */
@Service
class AiAnalysisService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val aiAnalysisJobRepository: AiAnalysisJobRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * FULL은 임베딩이 **전부** 끝나 있어야 하고, NAMING은 FULL이 DONE인 적이 있어야 한다.
     * 모드와 무관하게 살아 있는 잡이 없어야 한다 — 두 검사 사이의 경쟁은 DB의 부분 유니크가
     * 잡고, 그 위반을 같은 409로 번역해 두 경로가 같은 코드로 보이게 한다.
     */
    @Transactional
    fun request(galleryId: Long, userId: Long, mode: AiAnalysisMode): AiAnalysisJobResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        when (mode) {
            AiAnalysisMode.FULL -> validateEmbeddingComplete(galleryId)
            AiAnalysisMode.NAMING -> validateFullAnalysisDone(galleryId)
        }
        if (aiAnalysisJobRepository.existsByGalleryIdAndStatusIn(galleryId, AiJobStatus.ACTIVE)) {
            throw RecommendationException(RecommendationErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)
        }

        val job = try {
            aiAnalysisJobRepository.saveAndFlush(AiAnalysisJob(galleryId = galleryId, mode = mode))
        } catch (e: DataIntegrityViolationException) {
            throw RecommendationException(RecommendationErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)
        }

        log.info("AI 분석 요청: galleryId={}, jobId={}, mode={}", galleryId, job.requiredId, mode)

        return AiAnalysisJobResponse.from(job)
    }

    private fun validateEmbeddingComplete(galleryId: Long) {
        if (photoRepository.countEmbeddedByGalleryId(galleryId) == 0L) {
            throw RecommendationException(RecommendationErrorCode.NO_EMBEDDED_PHOTOS)
        }
        // 일부만 끝난 상태로 시작하면 나머지는 이번 잡에서 빠지고, 백분위·클러스터가 부분 집합 기준으로 잡힌다.
        // 임베더와 같은 대상 집합(PENDING 제외)이 전부 벡터를 가질 때만 시작한다.
        if (photoRepository.countByGalleryIdAndStatusNotAndNotEmbedded(galleryId, PhotoStatus.PENDING) > 0L) {
            throw RecommendationException(RecommendationErrorCode.EMBEDDING_NOT_COMPLETE)
        }
    }

    private fun validateFullAnalysisDone(galleryId: Long) {
        val fullDone = aiAnalysisJobRepository.existsByGalleryIdAndModeAndStatus(
            galleryId,
            AiAnalysisMode.FULL,
            AiJobStatus.DONE,
        )
        if (!fullDone) {
            throw RecommendationException(RecommendationErrorCode.FULL_ANALYSIS_NOT_DONE)
        }
    }

    /** 가장 최근 잡. 프론트가 "AI 분석" 버튼 옆에 상태를 보여 주는 데 쓴다. */
    @Transactional(readOnly = true)
    fun latest(galleryId: Long, userId: Long): AiAnalysisJobResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val job = aiAnalysisJobRepository.findFirstByGalleryIdOrderByIdDesc(galleryId)
            ?: throw RecommendationException(RecommendationErrorCode.ANALYSIS_JOB_NOT_FOUND)

        return AiAnalysisJobResponse.from(job)
    }
}
