package com.soma.wes.retouch.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "보정사진 풀에 사진을 담는 요청")
data class AddRetouchPhotosRequest(

    @field:NotEmpty
    @field:Schema(
        description = "담을 원본 사진 id. 이번 회차에 이미 담긴 사진이 하나라도 섞여 있으면 " +
            "한 장도 담기지 않고 통째로 거절된다. 이전 회차에 담았던 사진은 다시 담을 수 있다.",
    )
    val photoIds: List<Long>,
)
