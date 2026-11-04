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

    @field:Schema(
        description = "실행기가 잡을 집어 RUNNING이 된 시각. PENDING이면 null이고, 집은 서버가 멈춰 잡이 PENDING으로 되돌아가면 다시 null이 된다.",
        example = "2026-11-04T10:15:31+09:00",
    )
    val startedAt: ZonedDateTime?,

    @field:Schema(description = "잡이 DONE 또는 FAILED로 닫힌 시각. 끝나기 전에는 null이다.", example = "2026-11-04T10:15:40+09:00")
    val finishedAt: ZonedDateTime?,

    @field:Schema(description = "FAILED일 때 워커가 남긴 오류. 그 외에는 null.")
    val error: String?,

    @field:Schema(description = "추천을 요청해 잡이 만들어진 시각. 저장된 잡이면 늘 채워진다.", example = "2026-11-04T10:15:30+09:00")
    val createdAt: ZonedDateTime?,

    @field:Schema(description = "요청한 추천 장수. null이면 계약 장수 기반 폴더 쿼터를 썼다.")
    val targetCount: Int? = null,
    @field:Schema(description = "완료된 이번 잡의 추천 수. targetCount보다 후보가 적을 수 있다.")
    val recommendedCount: Int? = null,
    @field:Schema(description = "요청한 장수(targetCount)에서 모자란 추천 수(0 이상). targetCount 없이 요청했거나 잡이 끝나기 전이면 null이다.", example = "2")
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
