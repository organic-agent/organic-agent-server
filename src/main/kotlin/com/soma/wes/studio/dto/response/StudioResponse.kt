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
    val inflowChannel: String?,
    val createdAt: ZonedDateTime?,
) {

    companion object {
        fun from(studio: Studio) = StudioResponse(
            id = studio.requiredId,
            workspaceId = studio.workspaceId,
            name = studio.name,
            galleryUrl = studio.galleryUrl,
            inflowChannel = studio.inflowChannel,
            createdAt = studio.createdAt,
        )
    }
}
