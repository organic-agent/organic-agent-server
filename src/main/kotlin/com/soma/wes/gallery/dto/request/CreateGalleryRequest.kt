package com.soma.wes.gallery.dto.request

import com.soma.wes.gallery.domain.ShootType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
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

    // Gallery.MIN_SELECTABLE_PHOTO_COUNT과 같은 값이다. @Min은 Long 리터럴만 받아 상수를 쓸 수 없다.
    @field:Min(1)
    @field:Schema(
        description = "부부가 최종적으로 고를 사진 장수(계약 장수). 지정하지 않으면 제한 없이 고를 수 있다.",
        example = "50",
    )
    val maxSelectablePhotoCount: Int? = null,

    @field:Schema(
        description = "촬영 종류. REHEARSAL(리허설) | CEREMONY(본식) | OTHER. AI 폴더의 큰 분류 목록이 이 값으로 갈린다. 지정하지 않으면 REHEARSAL.",
        example = "REHEARSAL",
    )
    val shootType: ShootType = ShootType.REHEARSAL,
)
