package com.soma.wes.retouch.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "되묻기 선택지. 원문 해석의 갈래여야 하고, 새 보정 제안이면 안 된다.")
data class RefineRetouchOptionResponse(
    @field:Schema(description = "부부에게 보여줄 선택지 문구.", example = "신랑 넥타이를 바르게 정리해 주세요")
    val label: String,

    @field:Schema(description = "이 선택지의 근거가 된 원문 구절 그대로.", example = "신랑 넥타이 바로 해주세요")
    val sourceSpan: String,
)
