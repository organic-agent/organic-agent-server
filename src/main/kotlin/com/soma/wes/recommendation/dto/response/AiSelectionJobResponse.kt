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
        description = "PENDING(대기) → RUNNING → DONE 또는 FAILED. 추천 표시는 DONE 전에 먼저 생길 수 " +
            "있다 — 이유 문장 채우기가 같은 잡에서 이어 돌기 때문이다(reasonReady로 판별).",
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

    val prompt: String? = null,
    val targetCount: Int? = null,
    @field:Schema(description = "해석을 마친 범위. null이면 전체 갤러리이며, 해석 여부는 queryResolved로 구분한다.")
    val resolvedDetailFolderIds: List<Long>? = null,
    val resolvedTargetCount: Int? = null,
    val queryResolved: Boolean = false,
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
            prompt = job.prompt,
            targetCount = job.targetCount,
            resolvedDetailFolderIds = job.resolvedQuery?.detailFolderIds,
            resolvedTargetCount = job.resolvedQuery?.targetCount,
            queryResolved = job.resolvedQuery != null,
            recommendedCount = (job.result?.get("k") as? Number)?.toInt(),
            shortfallCount = (job.result?.get("shortfallCount") as? Number)?.toInt(),
        )
    }
}
