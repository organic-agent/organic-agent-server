package com.soma.wes.retouch.dto.response

import com.soma.wes.retouch.domain.RetouchRound
import com.soma.wes.retouch.domain.RetouchRoundStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "회차 하나의 상세. 항목마다 원본과 결과를 나란히 줘 전/후 비교가 된다")
data class RetouchRoundDetailResponse(

    @field:Schema(description = "1부터 시작하는 회차 번호")
    val roundNo: Int,

    val status: RetouchRoundStatus,

    val requestedAt: ZonedDateTime?,

    val completedAt: ZonedDateTime?,

    @field:Schema(description = "회차에 담긴 보정 요청. 갤러리에서 정한 노출 순서를 따른다")
    val photos: List<RetouchPhotoDetailResponse>,

    @field:Schema(description = "서명된 조회 URL의 남은 수명. 지나기 전에 다시 부르면 새 URL이 온다")
    val viewUrlTtlSeconds: Long,
) {

    companion object {
        fun of(
            round: RetouchRound,
            photos: List<RetouchPhotoDetailResponse>,
            viewUrlTtlSeconds: Long,
        ) = RetouchRoundDetailResponse(
            roundNo = round.roundNo,
            status = round.status,
            requestedAt = round.requestedAt,
            completedAt = round.completedAt,
            photos = photos,
            viewUrlTtlSeconds = viewUrlTtlSeconds,
        )
    }
}
