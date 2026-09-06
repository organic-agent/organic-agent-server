package com.soma.wes.category.service

import com.soma.wes.category.domain.CategorizationJob
import com.soma.wes.category.domain.CategorizationMode
import com.soma.wes.category.domain.CategorizationStatus
import com.soma.wes.category.dto.response.CategorizationJobResponse
import com.soma.wes.category.repository.CategorizationJobPhotoRepository
import com.soma.wes.category.repository.CategorizationJobRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 기존 categorization-job API와 관리자 액션을 최신 AI 분석 → Concept/Detail 물질화 경로에 연결한다.
 * 유사도 조회·Union-Find·레벨 설정은 폐기된 photo-clusters 도메인의 책임이므로 여기서 재구현하지 않는다.
 */
@Service
class CategorizationService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val jobRepository: CategorizationJobRepository,
    private val jobPhotoRepository: CategorizationJobPhotoRepository,
    private val aiCategoryFolderService: AiCategoryFolderService,
    private val clock: Clock,
) {
    @Transactional
    fun run(galleryId: Long, userId: Long): CategorizationJobResponse {
        galleryAccessPolicy.requireUploader(galleryId, userId)
        return runAsAdmin(galleryId)
    }

    /** 최고 관리자 워크플로가 사용자 신원을 가장하지 않고 같은 물질화 규칙을 실행하는 진입점. */
    @Transactional
    fun runAsAdmin(galleryId: Long): CategorizationJobResponse {
        val previousJobId = jobRepository.findFirstByGalleryIdOrderByCreatedAtDesc(galleryId)?.requiredId
        aiCategoryFolderService.createFromAnalysisAsAdmin(galleryId)
        val latest = latestResponse(galleryId)
            ?: error("AI category materialization completed without a categorization job")
        return if (latest.id == previousJobId) recordNoOpIncremental(galleryId) else latest
    }

    @Transactional(readOnly = true)
    fun latest(galleryId: Long, userId: Long): CategorizationJobResponse? {
        galleryAccessPolicy.requireViewer(galleryId, userId)
        return latestResponse(galleryId)
    }

    private fun latestResponse(galleryId: Long): CategorizationJobResponse? {
        val job = jobRepository.findFirstByGalleryIdOrderByCreatedAtDesc(galleryId) ?: return null
        return CategorizationJobResponse.of(job, jobPhotoRepository.findAllByJobIdOrderByPhotoId(job.requiredId))
    }

    private fun recordNoOpIncremental(galleryId: Long): CategorizationJobResponse {
        val initialCompleted = jobRepository.existsByGalleryIdAndModeAndStatus(
            galleryId,
            CategorizationMode.INITIAL,
            CategorizationStatus.SUCCEEDED,
        )
        val mode = if (initialCompleted) CategorizationMode.INCREMENTAL else CategorizationMode.INITIAL
        val now = ZonedDateTime.now(clock)
        val job = jobRepository.save(CategorizationJob(galleryId, mode).also { it.startedAt = now })
        job.complete(ZonedDateTime.now(clock))
        return CategorizationJobResponse.of(job)
    }
}
