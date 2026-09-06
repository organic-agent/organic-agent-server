package com.soma.wes.gallery.dto.request

import com.soma.wes.gallery.domain.Gallery
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.Min
import java.time.ZonedDateTime

data class UpdatePersonalGalleryRequest(
    @field:NotBlank @field:Size(max = Gallery.MAX_TITLE_LENGTH) val title: String,
    val selectionDeadline: ZonedDateTime? = null,
    @field:Min(1) val maxSelectablePhotoCount: Int? = null,
)
