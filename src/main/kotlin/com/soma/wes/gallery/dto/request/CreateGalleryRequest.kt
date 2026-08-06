package com.soma.wes.gallery.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.ZonedDateTime

@Schema(description = "갤러리 생성 요청")
data class CreateGalleryRequest(

    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(description = "갤러리 이름", example = "김철수 · 이영희 본식")
    val title: String,

    @field:Schema(
        description = "사진 선택 마감 기한. 지정하지 않으면 기한 없이 열어 둔다.",
        example = "2026-09-30T23:59:59+09:00",
    )
    val selectionDeadline: ZonedDateTime? = null,
)
