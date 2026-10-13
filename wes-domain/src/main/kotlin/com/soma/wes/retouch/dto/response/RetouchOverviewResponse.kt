package com.soma.wes.retouch.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "보정사진 페이지 전체. 회차 목록과 현재 회차, 남은 횟수를 담는다")
data class RetouchOverviewResponse(

    @field:Schema(description = "계약한 보정 횟수. null이면 제한이 없다")
    val maxRetouchRoundCount: Int?,

    @field:Schema(
        description = "더 제출할 수 있는 횟수. 제한이 없으면 null이고, 계약 횟수가 줄어 이미 넘겼다면 0이다. " +
            "DRAFTING 회차는 아직 제출 전이라 세지 않는다",
    )
    val remainingRoundCount: Int?,

    @field:Schema(description = "지금까지의 회차 전부, 회차 번호 순")
    val rounds: List<RetouchRoundSummaryResponse>,

    @field:Schema(
        description = "진행 중인 회차(DRAFTING 또는 REQUESTED)와 담긴 요청. " +
            "모든 회차가 끝났거나 아직 아무것도 담지 않았으면 null이다",
    )
    val currentRound: RetouchRoundResponse?,

    @field:Schema(description = "서명된 조회 URL의 남은 수명. 지나기 전에 다시 부르면 새 URL이 온다")
    val viewUrlTtlSeconds: Long,
) {

    companion object {

        fun of(
            maxRetouchRoundCount: Int?,
            submittedRoundCount: Int,
            rounds: List<RetouchRoundSummaryResponse>,
            currentRound: RetouchRoundResponse?,
            viewUrlTtlSeconds: Long,
        ) = RetouchOverviewResponse(
            maxRetouchRoundCount = maxRetouchRoundCount,
            remainingRoundCount = maxRetouchRoundCount?.let { (it - submittedRoundCount).coerceAtLeast(0) },
            rounds = rounds,
            currentRound = currentRound,
            viewUrlTtlSeconds = viewUrlTtlSeconds,
        )
    }
}
