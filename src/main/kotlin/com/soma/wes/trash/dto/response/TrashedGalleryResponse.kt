package com.soma.wes.trash.dto.response

import com.soma.wes.trash.repository.projection.TrashedGalleryRow
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Duration
import java.time.ZonedDateTime

@Schema(description = "휴지통의 갤러리")
data class TrashedGalleryResponse(
    val galleryId: Long,
    val title: String,

    @field:Schema(description = "함께 지워질 사진 수(개별 휴지통행 사진 포함)")
    val photoCount: Long,

    val deletedAt: ZonedDateTime,

    @field:Schema(description = "이 시각이 지나면 자동으로 물리 삭제된다")
    val expiresAt: ZonedDateTime,
) {

    companion object {
        fun of(row: TrashedGalleryRow, retention: Duration) = TrashedGalleryResponse(
            galleryId = row.galleryId,
            title = row.title,
            photoCount = row.photoCount,
            deletedAt = row.deletedAt,
            expiresAt = row.deletedAt + retention,
        )
    }
}
