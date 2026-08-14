package com.soma.wes.photo.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(
    description = "사진 휴지통 이동 요청. 한 장을 지워도 배치로 보낸다.",
)
data class DeletePhotosRequest(

    @field:NotEmpty
    @field:Schema(description = "휴지통으로 보낼 사진 id 목록", example = "[1, 2, 3]")
    val photoIds: List<Long>,
)
