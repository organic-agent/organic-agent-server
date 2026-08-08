package com.soma.wes.selection.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "선택 앨범에 사진을 담는 요청")
data class SelectPhotosRequest(

    @field:NotEmpty
    @field:Schema(
        description = "담을 사진 id. 이미 담긴 사진이 하나라도 섞여 있거나 다 담고 나서 계약 장수를 " +
            "넘긴다면, 한 장도 담기지 않고 통째로 거절된다.",
    )
    val photoIds: List<Long>,
)
