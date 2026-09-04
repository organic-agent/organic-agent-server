package com.soma.wes.analysis.dto.response

import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisMode
import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.domain.AnalysisStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "갤러리 AI 분석 잡의 상태. 요청 직후에는 PENDING이고, 단계가 돌면서 바뀐다.")
data class AnalysisJobResponse(
    val jobId: Long,
    val galleryId: Long,
    @field:Schema(description = "FULL(미리보기·임베딩 → 점수 → 그룹·이름 전체) · NAMING(이름·배정만).", example = "FULL")
    val mode: AnalysisMode,
    @field:Schema(
        description = "PENDING(대기) → RUNNING → DONE 또는 FAILED. DONE이면 모드의 마지막 단계까지 적재된 것이다.",
        example = "PENDING",
    )
    val status: AnalysisStatus,
    @field:Schema(
        description = "지금 어느 단계인가 — EMBED(미리보기·임베딩) · SCORE(사진별 점수) · CATEGORIZE(그룹·이름). 끝난 잡은 마지막 단계다.",
        example = "SCORE",
    )
    val stage: AnalysisStage?,
    @field:Schema(description = "그 단계의 상태. PENDING이면 아직 실행기가 집지 않았고, RUNNING이면 도는 중이다.", example = "RUNNING")
    val stageStatus: AnalysisStatus?,
    @field:Schema(description = "RUNNING인 잡의 진행률. 배치가 아직 안 남겼으면 null — 7000장 갤러리에서 수십 분짜리 진행 표시의 재료다.")
    val progress: Progress?,
    val startedAt: ZonedDateTime?,
    val finishedAt: ZonedDateTime?,
    @field:Schema(description = "FAILED일 때 남은 오류. 그 외에는 null.")
    val error: String?,
    val createdAt: ZonedDateTime?,
) {

    data class Progress(
        val processed: Int,
        val total: Int,
    )

    companion object {

        fun from(job: AnalysisJob): AnalysisJobResponse = AnalysisJobResponse(
            jobId = job.requiredId,
            galleryId = job.galleryId,
            mode = job.mode,
            status = job.status,
            stage = job.stage,
            stageStatus = job.stageStatus,
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
