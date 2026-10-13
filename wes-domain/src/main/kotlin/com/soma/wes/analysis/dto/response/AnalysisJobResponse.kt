package com.soma.wes.analysis.dto.response

import com.soma.wes.analysis.domain.AnalysisFailureCode
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
    @field:Schema(
        description = "FAILED 의 이유 코드. NOTHING_TO_ANALYZE(분석할 사진 없음) · SCORE_STAGE_DOWN(임베딩·점수 멈춤) · "
            + "CATEGORIZE_TIMEOUT · CATEGORIZE_FAILED(분류 실패) · FOLDER_FAILED(폴더 생성 실패). "
            + "FAILED 가 아니거나 코드가 생기기 전에 닫힌 잡이면 null.",
        nullable = true,
    )
    val errorCode: AnalysisFailureCode?,
    @field:Schema(description = "FAILED 일 때 사용자에게 보여 줄 문장. 내부 오류 문장은 내보내지 않는다. 그 외에는 null.", nullable = true)
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
            errorCode = job.errorCode,
            error = userMessageOf(job),
            createdAt = job.createdAt,
            finishedAt = job.finishedAt,
        )

        /** 내부 문장(`error` 컬럼)에는 Lambda 의 예외 메시지가 그대로 들어 있을 수 있어 화면에 내지 않는다. */
        private fun userMessageOf(job: AnalysisJob): String? {
            if (job.status != AnalysisStatus.FAILED) return null
            return job.errorCode?.userMessage ?: AnalysisFailureCode.UNKNOWN_USER_MESSAGE
        }
    }
}
