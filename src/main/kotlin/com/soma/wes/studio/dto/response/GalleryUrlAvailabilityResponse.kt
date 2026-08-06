package com.soma.wes.studio.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "공개 주소 사용 가능 여부")
data class GalleryUrlAvailabilityResponse(
    val galleryUrl: String,

    @field:Schema(
        description = "true라도 생성이 반드시 성공하지는 않는다. 확인과 생성 사이에 다른 사람이 " +
            "같은 주소를 채갈 수 있고, 최종 판단은 생성 API의 409다.",
    )
    val available: Boolean,
)
