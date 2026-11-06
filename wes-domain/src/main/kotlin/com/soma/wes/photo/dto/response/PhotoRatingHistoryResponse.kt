package com.soma.wes.photo.dto.response

import com.soma.wes.photo.domain.PhotoRatingHistory
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "사진 별점이 바뀐 기록 한 줄")
data class PhotoRatingHistoryResponse(
    @field:Schema(description = "기록 id. 클수록 나중 기록이다", example = "913")
    val id: Long,

    @field:Schema(description = "1~5점. null이면 이때 별점을 지웠다", example = "5", nullable = true)
    val score: Int?,

    @field:Schema(description = "매기거나 지운 참여자 id. 갤러리 멤버 목록의 userId와 맞춰 이름·색을 칠한다", example = "42")
    val ratedBy: Long,

    @field:Schema(description = "바뀐 시각", example = "2026-11-06T13:05:00+09:00")
    val ratedAt: ZonedDateTime,
) {

    companion object {
        fun from(history: PhotoRatingHistory) = PhotoRatingHistoryResponse(
            id = history.requiredId,
            score = history.score,
            ratedBy = history.ratedBy,
            ratedAt = history.createdAt,
        )
    }
}
