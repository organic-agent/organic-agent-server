package com.soma.wes.gallery.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "갤러리 선택 마감 기한 변경 요청")
data class ChangeSelectionDeadlineRequest(

    @field:Schema(
        description = "새 사진 선택 마감 기한. null을 보내면 기한이 없어진다.",
        example = "2026-09-30T23:59:59+09:00",
    )
    val selectionDeadline: ZonedDateTime? = null,
)
