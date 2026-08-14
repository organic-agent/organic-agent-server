package com.soma.wes.trash.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "사진 복원 요청")
data class RestorePhotosRequest(

    @field:NotEmpty
    @field:Schema(description = "휴지통에서 되살릴 사진 id 목록", example = "[1, 2, 3]")
    val photoIds: List<Long>,
)
