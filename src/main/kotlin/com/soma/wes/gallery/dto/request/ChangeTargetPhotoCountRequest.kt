package com.soma.wes.gallery.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min

@Schema(description = "갤러리의 계약 장수를 바꾸는 요청")
data class ChangeTargetPhotoCountRequest(

    // Gallery.MIN_TARGET_PHOTO_COUNT과 같은 값이다. @Min은 Long 리터럴만 받아 상수를 쓸 수 없다.
    @field:Min(1)
    @field:Schema(
        description = "부부가 최종적으로 고를 사진 장수. null을 보내면 제한이 없어진다.",
        example = "50",
    )
    val targetPhotoCount: Int? = null,
)
