package com.soma.wes.recommendation.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "비교샷 AI 판정 요청. 두 사진의 순서는 결과에 영향이 없다 — (a, b)와 (b, a)는 같은 판정이다.")
data class ComparePhotosRequest(

    @field:Schema(description = "비교할 첫 번째 사진 id", example = "101")
    val photoA: Long,

    @field:Schema(description = "비교할 두 번째 사진 id. photoA와 달라야 한다", example = "102")
    val photoB: Long,
)
