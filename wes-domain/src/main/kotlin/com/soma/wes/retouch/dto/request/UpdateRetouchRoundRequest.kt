package com.soma.wes.retouch.dto.request

import com.soma.wes.retouch.domain.RetouchPhoto
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "회차의 모든 사진에 적용되는 보정 요청을 적는 요청. 제출 전(DRAFTING)에는 몇 번이고 덮어쓴다")
data class UpdateRetouchRoundRequest(

    @field:Size(max = RetouchPhoto.MAX_REQUEST_TEXT_LENGTH)
    @field:Schema(description = "회차 전체 보정 요청. null이거나 공백뿐이면 지운다.", example = "전체적으로 톤을 밝게 맞춰주세요")
    val requestText: String? = null,
)
