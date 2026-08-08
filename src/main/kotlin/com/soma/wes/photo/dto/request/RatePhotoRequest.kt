package com.soma.wes.photo.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

@Schema(description = "사진에 별점을 매기는 요청")
data class RatePhotoRequest(

    // PhotoRating.MIN_SCORE·MAX_SCORE와 같은 값이다. @Min/@Max는 Long 리터럴만 받아 상수를 쓸 수 없다.
    @field:Min(1)
    @field:Max(5)
    @field:Schema(description = "1~5점. 같은 사진에 다시 보내면 덮어쓴다", example = "4")
    val score: Int,
)
