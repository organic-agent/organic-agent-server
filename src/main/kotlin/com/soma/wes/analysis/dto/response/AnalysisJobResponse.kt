package com.soma.wes.analysis.dto.response

import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.photo.repository.projection.GalleryAnalysisProgress
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "갤러리 AI 분석 잡의 상태. 요청 직후에는 ANALYZING이고, 점수가 다 차면 CATEGORIZING, 폴더가 만들어지면 DONE이다.")
data class AnalysisJobResponse(
    val jobId: Long,
    val galleryId: Long,
    @field:Schema(
        description = "ANALYZING(임베딩·점수 진행 중) → CATEGORIZING(그룹·이름 붙이는 중) → DONE(AI 폴더 생성됨) 또는 FAILED.",
        example = "ANALYZING",
    )
    val status: AnalysisStatus,
    @field:Schema(description = "사진 수 기준 진행. 7000장 갤러리에서 수십 분짜리 진행 표시의 재료다.")
    val progress: AnalysisProgressResponse,
    @field:Schema(description = "FAILED일 때 남은 오류. 그 외에는 null.")
    val error: String?,
    val createdAt: ZonedDateTime?,
    val finishedAt: ZonedDateTime?,
) {

    companion object {

        fun from(job: AnalysisJob, progress: GalleryAnalysisProgress): AnalysisJobResponse = AnalysisJobResponse(
            jobId = job.requiredId,
            galleryId = job.galleryId,
            status = job.status,
            progress = AnalysisProgressResponse.from(progress),
            error = job.error,
            createdAt = job.createdAt,
            finishedAt = job.finishedAt,
        )
    }
}
