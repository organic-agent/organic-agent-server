package com.soma.wes.studio.dto.response

import com.soma.wes.studio.domain.Studio
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "스튜디오")
data class StudioResponse(
    val id: Long,
    val name: String,
    val galleryUrl: String,
    val inflowChannel: String?,
    val createdAt: ZonedDateTime?,
) {

    companion object {
        fun from(studio: Studio) = StudioResponse(
            id = checkNotNull(studio.id) { "저장되지 않은 스튜디오는 응답할 수 없습니다." },
            name = studio.name,
            galleryUrl = studio.galleryUrl,
            inflowChannel = studio.inflowChannel,
            createdAt = studio.createdAt,
        )
    }
}

@Schema(description = "공개 주소 사용 가능 여부")
data class GalleryUrlAvailabilityResponse(
    val galleryUrl: String,

    @field:Schema(
        description = "true라도 생성이 반드시 성공하지는 않는다. 확인과 생성 사이에 다른 사람이 " +
            "같은 주소를 채갈 수 있고, 최종 판단은 생성 API의 409다.",
    )
    val available: Boolean,
)
