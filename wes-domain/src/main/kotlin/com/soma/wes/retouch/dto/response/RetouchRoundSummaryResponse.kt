package com.soma.wes.retouch.dto.response

import com.soma.wes.retouch.domain.RetouchRound
import com.soma.wes.retouch.domain.RetouchRoundStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "회차 목록에 나오는 회차 하나의 요약")
data class RetouchRoundSummaryResponse(

    @field:Schema(description = "1부터 시작하는 회차 번호")
    val roundNo: Int,

    val status: RetouchRoundStatus,

    @field:Schema(
        description = "부부가 회차를 제출해 REQUESTED가 된 시각. 제출 전(DRAFTING)이면 null이고, 작가가 작업 전에 선택을 다시 열면 null로 돌아간다.",
        example = "2026-11-04T10:15:30+09:00",
    )
    val requestedAt: ZonedDateTime?,

    @field:Schema(description = "작가가 결과를 다 올리고 회차를 끝낸(COMPLETED) 시각. 끝나기 전에는 null이다.", example = "2026-11-06T18:00:00+09:00")
    val completedAt: ZonedDateTime?,

    @field:Schema(description = "회차에 담긴 사진 수")
    val photoCount: Long,
) {

    companion object {
        fun of(round: RetouchRound, photoCount: Long) = RetouchRoundSummaryResponse(
            roundNo = round.roundNo,
            status = round.status,
            requestedAt = round.requestedAt,
            completedAt = round.completedAt,
            photoCount = photoCount,
        )
    }
}
