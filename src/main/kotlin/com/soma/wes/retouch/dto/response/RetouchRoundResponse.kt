package com.soma.wes.retouch.dto.response

import com.soma.wes.retouch.domain.RetouchRound
import com.soma.wes.retouch.domain.RetouchRoundStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "보정 회차 하나와 담긴 요청 전부")
data class RetouchRoundResponse(

    @field:Schema(description = "1부터 시작하는 회차 번호")
    val roundNo: Int,

    val status: RetouchRoundStatus,

    val requestedAt: ZonedDateTime?,

    val completedAt: ZonedDateTime?,

    @field:Schema(description = "회차에 담긴 보정 요청. 갤러리에서 정한 노출 순서를 따른다")
    val photos: List<RetouchPhotoResponse>,
) {

    companion object {
        fun of(round: RetouchRound, photos: List<RetouchPhotoResponse>) = RetouchRoundResponse(
            roundNo = round.roundNo,
            status = round.status,
            requestedAt = round.requestedAt,
            completedAt = round.completedAt,
            photos = photos,
        )
    }
}
