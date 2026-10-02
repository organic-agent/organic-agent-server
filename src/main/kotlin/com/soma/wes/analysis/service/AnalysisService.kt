package com.soma.wes.analysis.service

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.domain.AnalysisTrigger
import com.soma.wes.analysis.dto.response.AnalysisJobResponse
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.support.AnalysisJobCreator
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.photo.repository.projection.GalleryAnalysisProgress
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 작가의 "AI 분석" 버튼, 그리고 web 이 업로드 직후 보내는 요청. 잡 행을 만드는 것까지가 이 서비스의 일이다 —
 * 임베더 배정·categorize 호출·물질화는 5초마다 도는 [AnalysisPipelineService]가 잡의 상태를 보고 이어받는다.
 *
 * 이 요청이 없어도 서버가 스스로 잡을 만든다([com.soma.wes.analysis.support.AutoAnalysisStep]). 여기는 기다리지 않고
 * 바로 시작하는 빠른 길이다.
 */
@Service
class AnalysisService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val analysisJobRepository: AnalysisJobRepository,
    private val jobCreator: AnalysisJobCreator,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    /**
     * 멱등하다 — 몇 번을 불러도 갤러리에 살아 있는 잡은 하나이고, 같은 답이 돌아온다.
     * - 살아 있는 잡이 있으면 새로 만들지 않고 그 잡을 돌려준다(서버가 먼저 만들었거나, 같은 요청이 두 번 온 경우).
     * - 할 일이 없으면(가장 최근 잡이 끝났고 대상이 전부 분류돼 있다) 그 잡을 돌려준다.
     * - 그 밖에는 새 잡을 만든다. 만드는 사이 다른 쪽이 먼저 만들었으면(유니크 충돌) 그 잡을 돌려준다.
     *
     * 전에는 살아 있는 잡이 있으면 409 였다. web 이 그 409 를 받아 재요청을 예약하면서 잡이 연달아 두 번 만들어졌다.
     * 업로드가 끝난 사진이 한 장도 없으면 여전히 거절한다.
     */
    @Transactional
    fun request(galleryId: Long, userId: Long, conceptCount: Int? = null): AnalysisJobResponse {
        galleryAccessPolicy.requireUploader(galleryId, userId)

        if (!jobCreator.isRunnable) {
            throw AnalysisException(AnalysisErrorCode.AI_TASK_NOT_CONFIGURED)
        }
        val progress = progressOf(galleryId)
        val active = findActive(galleryId)
        if (active != null) return AnalysisJobResponse.from(active, progress)
        validateHasUploadedPhotos(progress)

        val latest = analysisJobRepository.findFirstByGalleryIdOrderByIdDesc(galleryId)
        if (latest != null && latest.status == AnalysisStatus.DONE && progress.isFullyCategorized) {
            return AnalysisJobResponse.from(latest, progress)
        }

        val job = try {
            jobCreator.create(
                galleryId = galleryId,
                conceptCount = conceptCount,
                trigger = AnalysisTrigger.USER,
                retryCount = 0,
                expected = progress.expected,
            )
        } catch (e: DataIntegrityViolationException) {
            findActive(galleryId) ?: throw AnalysisException(AnalysisErrorCode.ANALYSIS_JOB_ALREADY_ACTIVE)
        }
        return AnalysisJobResponse.from(job, progress)
    }

    private fun findActive(galleryId: Long): AnalysisJob? =
        analysisJobRepository.findFirstByGalleryIdAndStatusInOrderByIdDesc(galleryId, AnalysisStatus.ACTIVE)

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
