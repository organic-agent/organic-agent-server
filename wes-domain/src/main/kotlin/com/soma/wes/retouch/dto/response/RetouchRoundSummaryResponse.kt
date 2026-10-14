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

    val requestedAt: ZonedDateTime?,

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
