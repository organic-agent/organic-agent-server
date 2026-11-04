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
    @field:NotBlank
    @field:Size(max = Gallery.MAX_TITLE_LENGTH)
    @field:Schema(description = "갤러리 이름. 공백만으로는 안 되고 100자까지다.", example = "우리의 웨딩 리허설")
    val title: String,
    @field:Schema(
        description = "셀렉 목표일. 생략하면 이용 기간 만료 시각이 들어간다. 지난 시각이나 만료일보다 뒤의 날짜(날짜 단위 비교)는 거절하며, 지나도 고르기를 막지 않는 D-day 기준일이다.",
        example = "2026-12-31T23:59:59+09:00",
    )
    val selectionDeadline: ZonedDateTime? = null,
    @field:Min(1)
    @field:Schema(description = "최종으로 고를 사진 장수. 1 이상이어야 하고, 생략하면 제한이 없다.", example = "50")
    val maxSelectablePhotoCount: Int? = null,
    @field:Schema(description = "촬영 종류. AI 폴더의 컨셉 목록이 이 값으로 갈린다. 생략하면 REHEARSAL이다.", example = "REHEARSAL")
    val shootType: ShootType = ShootType.REHEARSAL,
    @field:Size(max = 20)
    @field:Schema(description = "free 또는 pro. 생략하면 무료. 무료는 계정당 한 번이며 프로는 새 갤러리에만 사용할 수 있다.", example = "free")
    val planId: String? = null,
    @field:Positive
    @field:Schema(description = "프로 개설에 사용할 본인의 미사용 쿠폰 id. 무료 개설에는 보내지 않는다.", example = "1")
    val couponId: Long? = null,
)
