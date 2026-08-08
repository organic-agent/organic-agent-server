package com.soma.wes.photo.dto.response

import com.soma.wes.photo.domain.PhotoRating
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "사진에 매겨진 별점. 사진당 하나이며 마지막에 매긴 사람이 덮어쓴다")
data class PhotoRatingResponse(
    val photoId: Long,

    @field:Schema(description = "1~5점")
    val score: Int,

    @field:Schema(description = "마지막으로 점수를 매긴 사용자 id. 작가일 수도 부부일 수도 있다")
    val ratedBy: Long,

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
