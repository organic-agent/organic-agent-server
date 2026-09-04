package com.soma.wes.folder.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "폴더에 사진을 더 담는 요청")
data class AddPhotosRequest(

    @field:NotEmpty
    @field:Schema(description = "담을 사진 id. 같은 부모 아래 어딘가에 이미 든 사진이 섞여 있으면 전체가 409로 거절된다")
    val photoIds: List<Long>,
)
