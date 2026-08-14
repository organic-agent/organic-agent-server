package com.soma.wes.gallery.dto.response

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "갤러리")
data class GalleryResponse(
    val id: Long,
    val studioId: Long,
    val title: String,
    val status: GalleryStatus,
    val selectionDeadline: ZonedDateTime?,

    @field:Schema(description = "부부가 최종적으로 고를 사진 장수. null이면 제한이 없다")
    val maxSelectablePhotoCount: Int?,

    val createdAt: ZonedDateTime?,
) {

    companion object {
        fun from(gallery: Gallery) = GalleryResponse(
            id = gallery.requiredId,
            studioId = gallery.studioId,
            title = gallery.title,
            status = gallery.status,
            selectionDeadline = gallery.selectionDeadline,
            maxSelectablePhotoCount = gallery.maxSelectablePhotoCount,
            createdAt = gallery.createdAt,
        )
    }
}
