package com.soma.wes.analysis.service

import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisMode
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.response.AnalysisJobResponse
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 작가의 "AI 분석" 버튼. 잡 행을 만들고 커밋 뒤 [AnalysisOrchestrator]에 넘기는 것까지가 이 서비스의 일이다 —
 * 어느 Lambda를 어떤 순서로 부르고 언제 닫는지는 오케스트레이터의 일이다.
 */
@Service
class AnalysisService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val analysisJobRepository: AnalysisJobRepository,
    private val stageInvoker: StageInvoker,
    private val orchestrator: AnalysisOrchestrator,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * FULL은 업로드가 끝난 사진이 있어야 하고, NAMING은 FULL이 DONE인 적이 있어야 한다.
     * 모드와 무관하게 살아 있는 잡이 없어야 한다 — 두 검사 사이의 경쟁은 DB의 부분 유니크가
     * 잡고, 그 위반을 같은 409로 번역해 두 경로가 같은 코드로 보이게 한다.
     */
    @Transactional
    fun request(galleryId: Long, userId: Long, mode: AnalysisMode, force: Boolean = false): AnalysisJobResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val job = createJob(galleryId, mode, force)

        log.info("AI 분석 요청: galleryId={}, jobId={}, mode={}, force={}", galleryId, job.requiredId, mode, force)

        return AnalysisJobResponse.from(job)
    }

    private fun createJob(galleryId: Long, mode: AnalysisMode, force: Boolean): AnalysisJob {
        // 기동이 아니라 여기서 실패한다. 로컬·테스트에는 실행기가 없는 것이 정상이라
        // 설정이 비어 있다고 앱을 못 뜨게 만들면 개발이 막힌다.
        validateInvokerConfigured(mode)
        when (mode) {
            AnalysisMode.FULL -> validateHasUploadedPhotos(galleryId)
            AnalysisMode.NAMING -> validateFullAnalysisDone(galleryId)
        }
        if (analysisJobRepository.existsByGalleryIdAndStatusIn(galleryId, AnalysisStatus.ACTIVE)) {
            throw AnalysisException(AnalysisErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)
        }

        val job = try {
            analysisJobRepository.saveAndFlush(AnalysisJob(galleryId = galleryId, mode = mode, force = force))
        } catch (e: DataIntegrityViolationException) {
            throw AnalysisException(AnalysisErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)
        }

        // 커밋 뒤에 부른다 — Lambda가 아직 안 보이는 행을 집으려다 실패하면 안 된다.
        val jobId = job.requiredId
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = orchestrator.dispatch(jobId)
            },
        )
        return job
    }

    private fun validateInvokerConfigured(mode: AnalysisMode) {
        if (mode.stages.any { !stageInvoker.isAvailable(it) }) {
            throw AnalysisException(AnalysisErrorCode.STAGE_NOT_CONFIGURED)
        }
    }

    private fun validateHasUploadedPhotos(galleryId: Long) {
        if (photoRepository.countByGalleryIdAndStatusNot(galleryId, PhotoStatus.PENDING) == 0L) {
            throw AnalysisException(AnalysisErrorCode.NO_PHOTOS_TO_ANALYZE)
        }
    }

    private fun validateFullAnalysisDone(galleryId: Long) {
        val fullDone = analysisJobRepository.existsByGalleryIdAndModeAndStatus(
            galleryId,
            AnalysisMode.FULL,
            AnalysisStatus.DONE,
        )
        if (!fullDone) {
            throw AnalysisException(AnalysisErrorCode.FULL_ANALYSIS_NOT_DONE)
        }
    }

    /** 가장 최근 잡. 프론트가 "AI 분석" 버튼 옆에 상태를 보여 주는 데 쓴다. */
    @Transactional(readOnly = true)
    fun latest(galleryId: Long, userId: Long): AnalysisJobResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val job = analysisJobRepository.findFirstByGalleryIdOrderByIdDesc(galleryId)
            ?: throw AnalysisException(AnalysisErrorCode.ANALYSIS_JOB_NOT_FOUND)

        return AnalysisJobResponse.from(job)
    }
}
