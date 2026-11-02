package com.soma.wes.photo.dto.response

import com.soma.wes.photo.domain.PhotoRating
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "사진에 매겨진 별점. 사진당 하나이며 마지막에 매긴 사람이 덮어쓴다")
data class PhotoRatingResponse(
    @field:Schema(description = "별점이 있는 사진 id", example = "101")
    val photoId: Long,

    @field:Schema(description = "1~5점", example = "5")
    val score: Int,

    @field:Schema(description = "마지막으로 점수를 매긴 참여자 id", example = "42")
    val ratedBy: Long,

    @field:Schema(description = "마지막 별점 수정 시각", example = "2026-11-02T09:30:00Z")
    val updatedAt: ZonedDateTime?,
) {

    companion object {
        fun from(rating: PhotoRating) = PhotoRatingResponse(
            photoId = rating.photoId,
            score = rating.score,
            ratedBy = rating.ratedBy,
            updatedAt = rating.updatedAt,
        )
    }
}
