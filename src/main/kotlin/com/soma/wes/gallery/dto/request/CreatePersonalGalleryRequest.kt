package com.soma.wes.gallery.dto.request

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.ShootType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.Min
import java.time.ZonedDateTime

data class CreatePersonalGalleryRequest(
    @field:NotBlank val checkoutId: String,
    @field:NotBlank @field:Size(max = Gallery.MAX_TITLE_LENGTH) val title: String,
    val selectionDeadline: ZonedDateTime? = null,
    @field:Min(1) val maxSelectablePhotoCount: Int? = null,
    val shootType: ShootType = ShootType.REHEARSAL,
)
