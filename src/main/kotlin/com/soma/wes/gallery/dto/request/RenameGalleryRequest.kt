package com.soma.wes.gallery.dto.request

import com.soma.wes.gallery.domain.Gallery
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "갤러리 이름 변경 요청")
data class RenameGalleryRequest(

    @field:NotBlank
    @field:Size(max = Gallery.MAX_TITLE_LENGTH)
    @field:Schema(description = "새 갤러리 이름", example = "김철수 · 이영희 본식")
    val title: String,
)
