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
    val createdAt: ZonedDateTime?,
) {

    companion object {
        fun from(gallery: Gallery) = GalleryResponse(
            id = checkNotNull(gallery.id) { "저장되지 않은 갤러리는 응답할 수 없습니다." },
            studioId = gallery.studioId,
            title = gallery.title,
            status = gallery.status,
            selectionDeadline = gallery.selectionDeadline,
            createdAt = gallery.createdAt,
        )
    }
}
