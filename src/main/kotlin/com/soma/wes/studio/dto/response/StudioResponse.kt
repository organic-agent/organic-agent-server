package com.soma.wes.studio.dto.response

import com.soma.wes.studio.domain.Studio
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "스튜디오")
data class StudioResponse(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val galleryUrl: String,
    @field:Schema(description = "과거 운영 데이터 호환용 필드. 공개 생성 요청에서는 받지 않는다.", deprecated = true)
    val inflowChannel: String?,
    val contact: String?,
    val description: String?,
    val createdAt: ZonedDateTime?,
) {

    companion object {
        fun from(studio: Studio) = StudioResponse(
            id = studio.requiredId,
            workspaceId = studio.workspaceId,
            name = studio.name,
            galleryUrl = studio.galleryUrl,
            inflowChannel = studio.inflowChannel,
            contact = studio.contact,
            description = studio.description,
            createdAt = studio.createdAt,
        )
    }
}
