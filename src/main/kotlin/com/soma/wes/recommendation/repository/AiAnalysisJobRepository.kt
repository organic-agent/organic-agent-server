package com.soma.wes.recommendation.repository

import com.soma.wes.recommendation.domain.AiAnalysisJob
import com.soma.wes.recommendation.domain.AiAnalysisMode
import com.soma.wes.recommendation.domain.AiJobStatus
import org.springframework.data.jpa.repository.JpaRepository

interface AiAnalysisJobRepository : JpaRepository<AiAnalysisJob, Long> {

    /** 갤러리의 가장 최근 잡. 이력이 쌓이므로 id가 큰 것이 최근이다. */
    fun findFirstByGalleryIdOrderByIdDesc(galleryId: Long): AiAnalysisJob?

    /** 모드별 가장 최근의 특정 상태 잡. 폴더 생성이 "최신 DONE naming 잡"을 고를 때 쓴다. */
    fun findFirstByGalleryIdAndModeAndStatusOrderByIdDesc(
        galleryId: Long,
        mode: AiAnalysisMode,
        status: AiJobStatus,
    ): AiAnalysisJob?

    fun existsByGalleryIdAndStatusIn(galleryId: Long, statuses: Collection<AiJobStatus>): Boolean

    fun existsByGalleryIdAndModeAndStatus(galleryId: Long, mode: AiAnalysisMode, status: AiJobStatus): Boolean
}
