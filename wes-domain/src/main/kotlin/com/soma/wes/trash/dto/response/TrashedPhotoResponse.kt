package com.soma.wes.trash.dto.response

import com.soma.wes.trash.repository.projection.TrashedPhotoRow
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Duration
import java.time.ZonedDateTime

@Schema(description = "휴지통의 사진")
data class TrashedPhotoResponse(
    val photoId: Long,
    val originalFileName: String,
    val deletedAt: ZonedDateTime,

    @field:Schema(description = "이 시각이 지나면 자동으로 물리 삭제된다")
    val expiresAt: ZonedDateTime,

    @field:Schema(description = "휴지통 화면에 그릴 서명 URL. 업로드가 끝나지 않았던(PENDING) 사진은 null")
    val viewUrl: String?,
) {

    companion object {
        fun of(row: TrashedPhotoRow, retention: Duration, viewUrl: String?) = TrashedPhotoResponse(
            photoId = row.photoId,
            originalFileName = row.originalFileName,
            deletedAt = row.deletedAt,
            expiresAt = row.deletedAt + retention,
            viewUrl = viewUrl,
        )
    }
}
