package com.soma.wes.selection.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "선택 앨범에서 사진을 빼는 요청")
data class DeselectPhotosRequest(

    @field:NotEmpty
    @field:Schema(description = "뺄 사진 id. 앨범에 없는 id가 섞여 있어도 나머지는 빠진다")
    val photoIds: List<Long>,
)
