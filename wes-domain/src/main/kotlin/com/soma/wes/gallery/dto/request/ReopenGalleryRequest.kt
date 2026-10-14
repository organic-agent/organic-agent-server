package com.soma.wes.gallery.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "갤러리 재오픈 요청")
data class ReopenGalleryRequest(

    @field:Schema(
        description = "새로 잡는 사진 선택 마감 기한. 지정하지 않으면 기한 없이 열어 둔다.",
        example = "2026-09-30T23:59:59+09:00",
    )
    val selectionDeadline: ZonedDateTime? = null,
)
