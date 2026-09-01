package com.soma.wes.recommendation.dto.response

import com.soma.wes.recommendation.domain.AiAnalysisJob
import com.soma.wes.recommendation.domain.AiAnalysisMode
import com.soma.wes.recommendation.domain.AiJobStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "갤러리 AI 분석 잡의 상태. 요청 직후에는 PENDING이고, 분석 배치가 집어가면서 바뀐다.")
data class AiAnalysisJobResponse(

    val jobId: Long,

    val galleryId: Long,

    @field:Schema(description = "FULL(사진별 분석 전체) 또는 NAMING(이름·배정만).", example = "FULL")
    val mode: AiAnalysisMode,

    @field:Schema(
        description = "PENDING(대기) → RUNNING → DONE 또는 FAILED. DONE이면 사진마다 그룹·피사체·점수·클러스터가 적재된 것이다.",
        example = "PENDING",
    )
    val status: AiJobStatus,

    @field:Schema(description = "RUNNING인 FULL 잡의 진행률. 배치가 아직 안 남겼으면 null — 7000장 갤러리에서 30~40분짜리 진행 표시의 재료다.")
    val progress: Progress?,

    val startedAt: ZonedDateTime?,

    val finishedAt: ZonedDateTime?,

    @field:Schema(description = "FAILED일 때 분석 배치가 남긴 오류. 그 외에는 null.")
    val error: String?,

    val createdAt: ZonedDateTime?,
) {

    data class Progress(
        val processed: Int,
        val total: Int,
    )

    companion object {

        fun from(job: AiAnalysisJob): AiAnalysisJobResponse = AiAnalysisJobResponse(
            jobId = job.requiredId,
            galleryId = job.galleryId,
            mode = job.mode,
            status = job.status,
            progress = progressOf(job.result),
            startedAt = job.startedAt,
            finishedAt = job.finishedAt,
            error = job.error,
            createdAt = job.createdAt,
        )

        /** `result.progress`는 배치가 주기적으로 UPDATE 하는 값이라 형태를 신뢰하지 않는다 — 숫자 둘이 아니면 없는 것으로 본다. */
        private fun progressOf(result: Map<String, Any?>?): Progress? {
            val progress = result?.get("progress") as? Map<*, *> ?: return null
            val processed = progress["processed"] as? Number ?: return null
            val total = progress["total"] as? Number ?: return null
            return Progress(processed = processed.toInt(), total = total.toInt())
        }
    }
}
