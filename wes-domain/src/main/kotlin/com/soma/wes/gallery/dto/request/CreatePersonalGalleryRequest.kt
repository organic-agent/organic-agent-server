package com.soma.wes.gallery.dto.request

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.ShootType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class CreatePersonalGalleryRequest(
    @field:Size(max = 36)
    @field:Schema(description = "이전에 발급한 테스트 이용권의 호환용 id. 신규 무료·프로 개설에는 보내지 않는다.")
    val checkoutId: String? = null,
    @field:NotBlank @field:Size(max = Gallery.MAX_TITLE_LENGTH) val title: String,
    val selectionDeadline: ZonedDateTime? = null,
    @field:Min(1) val maxSelectablePhotoCount: Int? = null,
    val shootType: ShootType = ShootType.REHEARSAL,
    @field:Size(max = 20)
    @field:Schema(description = "free 또는 pro. 생략하면 무료. 무료는 계정당 한 번이며 프로는 새 갤러리에만 사용할 수 있다.", example = "free")
    val planId: String? = null,
    @field:Positive
    @field:Schema(description = "프로 개설에 사용할 본인의 미사용 쿠폰 id. 무료 개설에는 보내지 않는다.", example = "1")
    val couponId: Long? = null,
)
