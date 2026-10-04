package com.soma.wes.recommendation.dto.response

import com.soma.wes.recommendation.domain.AiJobStatus
import com.soma.wes.recommendation.domain.AiSelectionJob
import com.soma.wes.recommendation.domain.AiSelectionMode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "AI 추천 잡의 상태. 요청 직후에는 PENDING이고, AI 워커가 집어가면서 바뀐다.")
data class AiSelectionJobResponse(

    val jobId: Long,

    val selectionId: Long,

    @field:Schema(description = "DRAFT(첫 라운드) 또는 REFINE(담기·거절을 반영한 다음 라운드). 서버가 셀렉 이력으로 정한다.", example = "DRAFT")
    val mode: AiSelectionMode,

    @field:Schema(
        description = "PENDING(대기) → RUNNING → DONE 또는 FAILED.",
        example = "PENDING",
    )
    val status: AiJobStatus,

    @field:Schema(description = "요청 시점의 기준 AI 폴더 세트 키. 현재 보는 세트와 다르면 추천을 다시 받아야 한다.")
    val folderSetJobId: Long?,

    @field:Schema(description = "이 잡의 범위 세부폴더. null이면 갤러리 전체 라운드다.")
    val detailFolderId: Long?,

    @field:Schema(description = "이 잡이 만든 추천 라운드 번호. 끝나기 전에는 null.")
    val round: Int?,

    val startedAt: ZonedDateTime?,

    val finishedAt: ZonedDateTime?,

    @field:Schema(description = "FAILED일 때 워커가 남긴 오류. 그 외에는 null.")
    val error: String?,

    val createdAt: ZonedDateTime?,

    @field:Schema(description = "요청한 추천 장수. null이면 계약 장수 기반 폴더 쿼터를 썼다.")
    val targetCount: Int? = null,
    @field:Schema(description = "완료된 이번 잡의 추천 수. targetCount보다 후보가 적을 수 있다.")
    val recommendedCount: Int? = null,
    val shortfallCount: Int? = null,
) {

    companion object {

        fun from(job: AiSelectionJob): AiSelectionJobResponse = AiSelectionJobResponse(
            jobId = job.requiredId,
            selectionId = job.selectionId,
            mode = job.mode,
            status = job.status,
            folderSetJobId = job.analysisJobId,
            detailFolderId = job.detailFolderId,
            round = job.round,
            startedAt = job.startedAt,
            finishedAt = job.finishedAt,
            error = job.error,
            createdAt = job.createdAt,
            targetCount = job.targetCount,
            recommendedCount = (job.result?.get("k") as? Number)?.toInt(),
            shortfallCount = (job.result?.get("shortfallCount") as? Number)?.toInt(),
        )
    }
}
