package com.soma.wes.gallery.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

data class RequestSelectionIncreaseRequest(
    @field:Min(1)
    @field:Schema(
        description = "늘려 달라는 최종 셀렉 장수. 스튜디오 갤러리이고 계약 장수가 정해져 있을 때만 받으며, 지금 계약 장수보다 커야 한다.",
        example = "80",
    )
    val requestedCount: Int,
    @field:Size(max = 300)
    @field:Schema(description = "작가에게 보내는 사유. 300자까지이고, 생략하면 장수만 알림에 실린다.", example = "본식 사진이 많아 30장 더 고르고 싶어요.")
    val message: String? = null,
)
