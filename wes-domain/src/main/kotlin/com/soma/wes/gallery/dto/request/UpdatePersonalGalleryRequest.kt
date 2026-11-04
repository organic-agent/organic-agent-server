package com.soma.wes.gallery.dto.request

import com.soma.wes.gallery.domain.Gallery
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.Min
import java.time.ZonedDateTime

data class UpdatePersonalGalleryRequest(
    @field:NotBlank
    @field:Size(max = Gallery.MAX_TITLE_LENGTH)
    @field:Schema(description = "바꿀 갤러리 이름. 공백만으로는 안 되고 100자까지다. 소유자와 파트너 모두 고칠 수 있다.", example = "우리의 웨딩 리허설")
    val title: String,
    @field:Schema(
        description = "셀렉 목표일. null이면 목표일을 지운다. 지난 시각이나 이용 기간 만료일보다 뒤의 날짜(날짜 단위 비교)는 거절한다.",
        example = "2026-12-31T23:59:59+09:00",
    )
    val selectionDeadline: ZonedDateTime? = null,
    @field:Min(1)
    @field:Schema(description = "최종으로 고를 사진 장수. 1 이상이어야 하고, null이면 장수 제한을 없앤다.", example = "50")
    val maxSelectablePhotoCount: Int? = null,
)
