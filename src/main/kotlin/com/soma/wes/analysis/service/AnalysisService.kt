package com.soma.wes.analysis.service

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisJobEventType
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.dto.response.AnalysisJobResponse
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.port.AiTaskSender
import com.soma.wes.analysis.support.AnalysisJobEventRecorder
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.photo.repository.projection.GalleryAnalysisProgress
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 작가의 "AI 분석" 버튼(프론트는 업로드 큐가 비면 자동으로도 부른다). ANALYZING 잡 행을 만드는 것까지가 이 서비스의 일이다 —
 * 임베더 배정·categorize 호출·물질화는 5초마다 도는 [AnalysisPipelineService]가 잡의 상태를 보고 이어받는다.
 */
@Service
class AnalysisService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val analysisJobRepository: AnalysisJobRepository,
    private val aiTaskSender: AiTaskSender,
    private val eventRecorder: AnalysisJobEventRecorder,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 업로드가 끝난 사진이 있어야 하고 살아 있는 잡이 없어야 한다 — 두 검사 사이의 경쟁은 DB의 부분 유니크가
     * 잡고, 그 위반을 같은 409로 번역해 두 경로가 같은 코드로 보이게 한다. 모드·force는 없다: 재분석은 관리자 리셋이다.
     */
    @Transactional
    fun request(galleryId: Long, userId: Long, conceptCount: Int? = null): AnalysisJobResponse {
        galleryAccessPolicy.requireUploader(galleryId, userId)

        // 기동이 아니라 여기서 실패한다. 로컬·테스트에는 실행기가 없는 것이 정상이라
        // 설정이 비어 있다고 앱을 못 뜨게 만들면 개발이 막힌다.
        validateInvokerConfigured()
        val progress = progressOf(galleryId)
        validateHasUploadedPhotos(progress)
        if (analysisJobRepository.existsByGalleryIdAndStatusIn(galleryId, AnalysisStatus.ACTIVE)) {
            throw AnalysisException(AnalysisErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)
        }

        val job = try {
            analysisJobRepository.saveAndFlush(AnalysisJob(galleryId = galleryId, conceptCount = conceptCount))
        } catch (e: DataIntegrityViolationException) {
            throw AnalysisException(AnalysisErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)
        }

        log.info(
            "event=job.created job={} gallery={} expected={} conceptCount={}",
            job.requiredId, galleryId, progress.expected, conceptCount,
        )
        eventRecorder.record(
            job.requiredId, galleryId, AnalysisJobEventType.CREATED,
            mapOf("expected" to progress.expected, "conceptCount" to conceptCount),
        )

        return AnalysisJobResponse.from(job, progress)
    }

    private fun validateInvokerConfigured() {
        val required = listOf(AiTaskDto.Embed::class, AiTaskDto.Categorize::class) +
            if (properties.gpu.enabled) emptyList() else listOf(AiTaskDto.Score::class)
        if (required.any { !aiTaskSender.isAvailable(it) }) {
            throw AnalysisException(AnalysisErrorCode.AI_TASK_NOT_CONFIGURED)
        }
    }

    private fun validateHasUploadedPhotos(progress: GalleryAnalysisProgress) {
        if (progress.expected == 0L) {
            throw AnalysisException(AnalysisErrorCode.NO_PHOTOS_TO_ANALYZE)
        }
    }

    /** 가장 최근 잡과 지금 진행. 프론트가 "AI 분석" 버튼 옆에 상태를 보여 주는 데 쓴다. */
    @Transactional(readOnly = true)
    fun latest(galleryId: Long, userId: Long): AnalysisJobResponse {
        galleryAccessPolicy.requireUploader(galleryId, userId)

        val job = analysisJobRepository.findFirstByGalleryIdOrderByIdDesc(galleryId)
            ?: throw AnalysisException(AnalysisErrorCode.ANALYSIS_JOB_NOT_FOUND)

        return AnalysisJobResponse.from(job, progressOf(galleryId))
    }

    private fun progressOf(galleryId: Long): GalleryAnalysisProgress =
        photoPipelineRepository.progressOf(galleryId, liveSince = ZonedDateTime.now(clock).minus(properties.uploadQuietAfter))
}
